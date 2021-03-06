package com.coocaa.prometheus.service.impl;

import com.alibaba.fastjson.JSON;
import com.coocaa.common.constant.TableConstant;
import com.coocaa.common.request.PageRequestBean;
import com.coocaa.common.request.RequestUtil;
import com.coocaa.core.log.exception.ApiException;
import com.coocaa.core.log.exception.ApiResultEnum;
import com.coocaa.core.log.response.ResponseHelper;
import com.coocaa.core.log.response.ResultBean;
import com.coocaa.core.mybatis.base.BaseServiceImpl;
import com.coocaa.core.secure.AiOpsUser;
import com.coocaa.core.secure.utils.SecureUtil;
import com.coocaa.core.tool.api.R;
import com.coocaa.core.tool.utils.*;
import com.coocaa.prometheus.entity.MetisException;
import com.coocaa.prometheus.entity.Task;
import com.coocaa.prometheus.input.MetisExceptionInputVo;
import com.coocaa.prometheus.mapper.MetisExceptionMapper;
import com.coocaa.prometheus.mapper.TaskMapper;
import com.coocaa.prometheus.output.MetisExceptionOutputVo;
import com.coocaa.prometheus.output.TaskOutputVo;
import com.coocaa.prometheus.service.MetisExceptionService;
import com.coocaa.user.entity.User;
import com.coocaa.user.feign.IUserClient;
import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @description: MetisException服务实现类
 * @author: dongyang_wu
 * @create: 2019-08-07 10:02
 */
@Service
@AllArgsConstructor
public class MetisExceptionServiceImpl extends BaseServiceImpl<MetisExceptionMapper, MetisException> implements MetisExceptionService {
    private MetisExceptionMapper metisExceptionMapper;
    private TaskMapper taskMapper;
    private IUserClient userClient;

    @Override
    public ResponseEntity<ResultBean> listByPage(PageRequestBean pageRequestBean) {
        RequestUtil.setDefaultPageBean(pageRequestBean);
        String conditionString = SqlUtil.getConditionString(pageRequestBean.getConditions(), pageRequestBean.getConditionConnection());
        User user = userClient.userById(SecureUtil.getUserId()).getData();
        String teamIds = user.getTeamIds();
        String orTeamIds = teamIds.replaceAll(",", " OR ");
        // 找出属于当前用户所在Team的所有task的Id
        List<String> taskIdList = taskMapper.selectTaskByTeamIds(orTeamIds).stream().map(task -> String.valueOf(task.getId())).collect(Collectors.toList());
        String taskIdConditionStr = String.join(",", taskIdList);
        // 增加条件 在用户管理的task下的所有异常列表
        conditionString = SqlUtil.addConditon(conditionString, TableConstant.TASK.TASK_ID, "IN", StringUtil.addBrackets(taskIdConditionStr));
        List<MetisException> list = metisExceptionMapper.getPageAll(pageRequestBean.getPage() * pageRequestBean.getCount(), pageRequestBean.getCount(), conditionString, pageRequestBean.getOrderBy(), pageRequestBean.getSortType());
        List<MetisExceptionOutputVo> resultList = list.stream().map(exception -> {
            MetisExceptionOutputVo vo = BeanUtil.copy(exception, MetisExceptionOutputVo.class);
            Task task = taskMapper.selectById(exception.getTaskId());
            vo.setTaskToStringMap(SqlUtil.map(exception.getTaskId(), task.getTaskName()).build());
            vo.setMatrixDataJsonMap(JSON.parseObject(exception.getMatrixDataJson(), Map.class));
            if (exception.getRecentUserId() == null || exception.getRecentUserId() == 0)
                return vo;
            else {
                R<User> rpcResult = userClient.userById(exception.getRecentUserId());
                if (rpcResult.getData() != null) {
                    ConcurrentHashMap<String, Map<String, String>> userToReasonMap = JSON.parseObject(exception.getUserToReasonJson(), (Type) ConcurrentHashMap.class);
                    Map<String, Map<String, String>> recentUserIdMap = new ConcurrentHashMap<>();
                    recentUserIdMap.put(rpcResult.getData().getId() + " " + rpcResult.getData().getName(), userToReasonMap.get(rpcResult.getData().getName()));
                    vo.setRecentUserReason(recentUserIdMap);
                    userToReasonMap.remove(rpcResult.getData().getName());
                    vo.setUserToReasonJsonMap(userToReasonMap);
                }
            }
            return vo;
        }).collect(Collectors.toList());
        Integer pageAllSize = metisExceptionMapper.getPageAllSize(conditionString);
        return ResponseHelper.OK(resultList, pageAllSize);
    }

    @Override
    public void update(MetisExceptionInputVo metisExceptionInputVo) {
        MetisException metisException = metisExceptionMapper.selectById(metisExceptionInputVo.getId());
        if (metisException == null)
            throw new ApiException(ApiResultEnum.ENTITY_NOT_EXIST);
        metisException.setStatus(metisExceptionInputVo.getStatus());
        String userToReasonJson = metisException.getUserToReasonJson();
        AiOpsUser user = SecureUtil.getUser();
        metisException.setRecentUserId(user.getUserId());
        String userName = user.getUserName();
        ConcurrentHashMap<String, Map<String, String>> userToReasonMap;
        String currentDate = new Date().getTime() / 1000 + "";
        // 不含JSON
        if (StringUtils.isEmpty(userToReasonJson)) {
            userToReasonMap = new ConcurrentHashMap<>();
            userToReasonMap.put(userName, SqlUtil.mapWithString(currentDate, metisExceptionInputVo.getReason()).build());
        }
        // 含JSON
        else {
            userToReasonMap = JSON.parseObject(userToReasonJson, (Type) ConcurrentHashMap.class);
            // 含相应用户的key
            if (userToReasonMap.containsKey(userName)) {
                Map<String, String> userReasons = userToReasonMap.get(userName);
                userReasons.put(currentDate, metisExceptionInputVo.getReason());
                if (userReasons.size() > 10) {
                    String firstKey = MapUtil.getFirstOrNull(userReasons);
                    userToReasonMap.remove(firstKey);
                }
                userToReasonMap.put(userName, userReasons);
            }
            // 不含相应用户的key
            else {
                userToReasonMap.put(userName, SqlUtil.mapWithString(currentDate, metisExceptionInputVo.getReason()).build());
            }

            // 判断是否应该删除
            if (userToReasonMap.size() > 10) {
                String firstKey = MapUtil.getFirstOrNull(userToReasonMap);
                userToReasonMap.remove(firstKey);
            }
        }
        metisException.setUserToReasonJson(JSON.toJSONString(userToReasonMap));
        metisException.updateById();
    }

}