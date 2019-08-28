/**
 * Copyright 2019 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.pallas.console.controller.plugin;

import com.alibaba.fastjson.JSONObject;
import com.vip.pallas.bean.PluginActionType;
import com.vip.pallas.bean.PluginCommands;
import com.vip.pallas.bean.PluginStates;
import com.vip.pallas.bean.PluginType;
import com.vip.pallas.console.utils.AuditLogUtil;
import com.vip.pallas.console.utils.AuthorizeUtil;
import com.vip.pallas.console.utils.SessionUtil;
import com.vip.pallas.console.vo.PluginAction;
import com.vip.pallas.console.vo.RemovePlugin;
import com.vip.pallas.entity.BusinessLevelExceptionCode;
import com.vip.pallas.exception.BusinessLevelException;
import com.vip.pallas.mybatis.entity.PluginCommand;
import com.vip.pallas.mybatis.entity.PluginRuntime;
import com.vip.pallas.mybatis.entity.PluginUpgrade;
import com.vip.pallas.service.ClusterService;
import com.vip.pallas.service.PallasPluginService;
import com.vip.pallas.utils.JsonUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vip.pallas.mybatis.entity.PluginUpgrade.*;

@RestController
@RequestMapping("/plugin")
public class PluginCommandActionController {

    @Autowired
    private PallasPluginService pluginService;

    @Autowired
    private ClusterService clusterService;

    @RequestMapping(path = "/remove.json")
    public void pluginRemoveAction(@RequestBody @Validated RemovePlugin plugin, HttpServletRequest request) {
    	if (!AuthorizeUtil.authorizePluginApprovePrivilege(request)) {
    		throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_FORBIDDEN, "无权限操作");
    	}

        if (null == clusterService.findByName(plugin.getClusterId())) {
            throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_INTERNAL_SERVER_ERROR, "cluster不存在");
        }

		// 只有新增或升级插件时才需要根据类别将文件释放到不同目录，删除插件时无需类别
        sendCommand("remove", plugin.getClusterId(), null, plugin.getPluginName(),
                plugin.getPluginVersion(), null);

        AuditLogUtil.log("post remove plugin: clusterId - {0}, pluginName - {1}, pluginVersion - {2}",
                plugin.getClusterId(), plugin.getPluginName(), plugin.getPluginVersion());
    }

    @RequestMapping(path = "/upgrade/action.json")
    public void pluginAction(@RequestBody @Validated PluginAction pluginAction, HttpServletRequest request) {
		if (!"recall".equals(pluginAction.getAction()) && !AuthorizeUtil.authorizePluginApprovePrivilege(request)) {
			throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_INTERNAL_SERVER_ERROR, "cluster不存在");
		}

        PluginUpgrade pUpgrade = pluginService.getPluginUpgrade(pluginAction.getPluginUpgradeId());
        if(pUpgrade == null) {
            throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_INTERNAL_SERVER_ERROR, "PluginUpgrade不存在");
        }

        int nextState = doUpgradeAction(pluginAction.getAction(), pUpgrade, pluginAction.getNodeIp());
        if(pUpgrade.getState() != nextState) {
            pluginService.setUpgradeState(SessionUtil.getLoginUser(request), pUpgrade.getId(), nextState);
        }
        if (null != pluginAction.getNodeIp()){
        	pluginService.concatGreyIps(pUpgrade.getId(), pluginAction.getNodeIp());
		}
    }

    @RequestMapping(value = "/sync.json", method = RequestMethod.POST, produces = "application/json;charset=UTF-8")
    public Map<String, Object> pluginSyncAction(@RequestBody JSONObject jsonParam) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        PluginStates pluginStates = JsonUtil.readValue(jsonParam.toJSONString(), PluginStates.class);
        List<PluginStates.Plugin> pluginList = pluginStates.getPlugins();
        List<PluginRuntime> plugins = pluginService.getPluginsByCluster(pluginStates.getClusterId());

        if(plugins != null && !plugins.isEmpty()){
            PluginCommands pluginCommands = new PluginCommands();
            pluginCommands.setClusterId(pluginStates.getClusterId());

            PluginCommands.Action downAndEnableAction = new PluginCommands.Action();
            downAndEnableAction.setActionType(PluginActionType.DOWN_AND_ENABLE);

            for (PluginRuntime runtime : plugins) {
                boolean isExists = false;

                String pluginName = runtime.getPluginName();
                String pluginVersion = runtime.getPluginVersion();

                if(pluginList != null){
                    for (PluginStates.Plugin plugin: pluginList) {
                        if(StringUtils.isNotBlank(pluginName) && pluginName.equals(plugin.getName())
                                && StringUtils.isNotBlank(pluginVersion) && pluginVersion.equals(plugin.getVersion())
                                && runtime.getPluginType() == plugin.getType().getValue()){
                            isExists = true;
                        }
                    }
                }

                if(!isExists && StringUtils.isNotBlank(pluginName) && StringUtils.isNotBlank(pluginVersion)){
                    PluginCommands.Plugin plugin = new PluginCommands.Plugin();
                    plugin.setName(pluginName);
                    plugin.setVersion(pluginVersion);
                    plugin.setType(PluginType.getPluginTypeByValue(runtime.getPluginType()));
                    downAndEnableAction.addPlugin(plugin);
                }
            }

            if(CollectionUtils.isNotEmpty(downAndEnableAction.getPlugins())){
                pluginCommands.addAction(downAndEnableAction);
            }

            resultMap.put("response", pluginCommands);
            resultMap.put("status", 0);
        }

        return resultMap;
    }

    private int doUpgradeAction(String action, PluginUpgrade pUpgrade, String nodeIp) {
        if (!pUpgrade.isFinished()) {
            String actionLowcase = action.toLowerCase();
            int currentStatus = pUpgrade.getState();
            switch (actionLowcase) {
                case "recall":
                    if (currentStatus != UPGRADE_STATUS_NEED_APPROVAL) {
                        break;
                    }
                    return UPGRADE_STATUS_CANCEL;
                case "deny":
                    if (currentStatus != UPGRADE_STATUS_NEED_APPROVAL) {
                        break;
                    }
                    return UPGRADE_STATUS_DENY;
                case "stop":
                    return UPGRADE_STATUS_CANCEL;
                case "done":
                    return UPGRADE_STATUS_DONE;
				case "download":
					sendCommand(actionLowcase, pUpgrade.getClusterId(), null,
							pUpgrade.getPluginName(), pUpgrade.getPluginVersion(), pUpgrade.getPluginType());
					return currentStatus <= UPGRADE_STATUS_DOWNLOAD ? UPGRADE_STATUS_DOWNLOAD : currentStatus;
				case "upgrade":
					// can only do upgrade-action in node level
					sendCommand(actionLowcase, pUpgrade.getClusterId(), nodeIp,
							pUpgrade.getPluginName(), pUpgrade.getPluginVersion(), pUpgrade.getPluginType());
					if (null != nodeIp){
						currentStatus = UPGRADE_STATUS_UPGRADE_GREY;
					}
                    return currentStatus <= UPGRADE_STATUS_UPGRADE ? UPGRADE_STATUS_UPGRADE : currentStatus;
                case "remove":
                    if (currentStatus != UPGRADE_STATUS_DONE) {
                        break;
                    }
                    sendCommand(actionLowcase, pUpgrade.getClusterId(), null,
							pUpgrade.getPluginName(), pUpgrade.getPluginVersion(), pUpgrade.getPluginType());
                    return UPGRADE_STATUS_REMOVE;
                default:
                    break;
            }
        }
        throw new BusinessLevelException(BusinessLevelExceptionCode.HTTP_INTERNAL_SERVER_ERROR, "该工单不支持该操作:" + action);
    }

    private void sendCommand(String action, String clusterId, String nodeIp, String pluginName, String pluginVersion, Integer pluginType){
		if (null != nodeIp){
			sendCommandByNodeIp(action, clusterId, nodeIp, pluginName, pluginVersion, pluginType);
		} else {
			List<String> nodeIpList = pluginService.getNodeIPsByCluster(clusterId);
			for(String ip : nodeIpList) {
				sendCommandByNodeIp(action, clusterId, ip, pluginName, pluginVersion, pluginType);
			}
		}
    }

	private void sendCommandByNodeIp(String action, String clusterId, String ip, String pluginName, String pluginVersion,
			Integer pluginType) {
		if ("".equals(ip)) { //忽略虚拟Runtime状态
			return;
		}
		PluginCommand cmd = new PluginCommand();
		cmd.setClusterId(clusterId);
		cmd.setCreateTime(new Date());
		cmd.setNodeIp(ip);
		cmd.setPluginName(pluginName);
		cmd.setPluginVersion(pluginVersion);
		// 只有新增或升级插件时才需要根据类别将文件释放到不同目录，删除插件时无需类别
		if (null != pluginType){
			cmd.setPluginType(pluginType);
		}

		switch (action){
			case "download":
				cmd.setCommand(PluginCommand.COMMAND_DOWNLOAD);
				break;
			case "upgrade":
				cmd.setCommand(PluginCommand.COMMAND_UPGRADE);
				break;
			case "remove":
				cmd.setCommand(PluginCommand.COMMAND_REMOVE);
				break;
			default:
				cmd.setCommand(PluginCommand.COMMAND_UNKNOWN);
		}
		pluginService.addPluginCommand(cmd);
	}
}