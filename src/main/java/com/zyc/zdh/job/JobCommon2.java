package com.zyc.zdh.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hubspot.jinjava.Jinjava;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;
import com.zyc.zdh.dao.*;
import com.zyc.zdh.entity.*;
import com.zyc.zdh.quartz.QuartzManager2;
import com.zyc.zdh.service.EtlTaskService;
import com.zyc.zdh.service.ZdhLogsService;
import com.zyc.zdh.service.impl.DataSourcesServiceImpl;
import com.zyc.zdh.util.*;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.shiro.SecurityUtils;
import org.quartz.TriggerUtils;
import org.quartz.impl.triggers.CronTriggerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class JobCommon2 {

    public static Logger logger = LoggerFactory.getLogger(JobCommon2.class);

    public static String myid = "";

    public static String web_application_id = "";

    public static LinkedBlockingDeque<ZdhLogs> linkedBlockingDeque = new LinkedBlockingDeque<ZdhLogs>();

    public static ConcurrentHashMap<String, Thread> chm = new ConcurrentHashMap<String, Thread>();
    public static ConcurrentHashMap<String, SSHUtil> chm_ssh = new ConcurrentHashMap<String, SSHUtil>();

    public static DelayQueue<RetryJobInfo> retryQueue = new DelayQueue<>();

    public static ThreadPoolExecutor threadPoolExecutor=new ThreadPoolExecutor(1, 1000,  500, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    public static void logThread(ZdhLogsService zdhLogsService) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        ZdhLogs log = JobCommon2.linkedBlockingDeque.take();
                        if (log != null) {
                            zdhLogsService.insert(log);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            }
        }).start();
    }

    /**
     * 超过次数限制会主动杀掉调度,并设置状态为error
     *
     * @param jobType SHELL,JDBC,FTP,CLASS
     * @param tli     return true 表示超过次数限制
     */
    public static boolean isCount(String jobType, TaskLogInstance tli) {
        logger.info("[" + jobType + "] JOB ,开始检查任务次数限制");
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,开始检查任务次数限制");

        QuartzJobMapper quartzJobMapper = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        TaskLogInstanceMapper taskLogInstanceMapper = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        //判断次数,上次任务完成重置次数
        if (tli.getLast_status() == null || tli.getLast_status().equals("finish")) {
            tli.setCount(0);
        }
        tli.setCount(tli.getCount() + 1);

        //设置执行的进度
        tli.setProcess("8");

        if (tli.getPlan_count().trim().equals("-1")) {
            logger.info("[" + jobType + "] JOB ,当前任务未设置执行次数限制");
            insertLog(tli, "info", "[" + jobType + "] JOB ,当前任务未设置执行次数限制");
        }

        if (!tli.getPlan_count().trim().equals("") && !tli.getPlan_count().trim().equals("-1")) {
            //任务有次数限制,重试多次后仍失败会删除任务
            System.out.println(tli.getCount() + "================" + tli.getPlan_count().trim());
            if (tli.getCount()-1 > Long.parseLong(tli.getPlan_count().trim())) {
                logger.info("[" + jobType + "] JOB 检任务次测到重试数超过限制,删除任务并直接返回结束");
                insertLog(tli, "info", "[" + jobType + "] JOB 检任务次测到重试数超过限制,删除任务并直接返回结束");
                QuartzJobInfo qji = new QuartzJobInfo();
                qji.setJob_id(tli.getJob_id());
                qji.setEtl_task_id(tli.getEtl_task_id());
                quartzManager2.deleteTask(tli, "finish", "error");
                quartzJobMapper.updateLastStatus(tli.getJob_id(), "error");

                insertLog(tli, "info", "[" + jobType + "] JOB ,结束调度任务");
                tli.setStatus(JobStatus.ERROR.getValue());
                updateTaskLog(tli, taskLogInstanceMapper);
                return true;
            }
        }
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,完成检查任务次数限制,未超过限制次数");
        updateTaskLog(tli, taskLogInstanceMapper);
        return false;
    }

    public static void debugInfo(Object obj) {
        Field[] fields = obj.getClass().getDeclaredFields();
        for (int i = 0, len = fields.length; i < len; i++) {
            // 对于每个属性，获取属性名
            String varName = fields[i].getName();
            try {
                // 获取原来的访问控制权限
                boolean accessFlag = fields[i].isAccessible();
                // 修改访问控制权限
                fields[i].setAccessible(true);
                // 获取在对象f中属性fields[i]对应的对象中的变量
                Object o;
                try {
                    o = fields[i].get(obj);
                    logger.info("传入的对象中包含一个如下的变量：" + varName + " = " + o);
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                // 恢复访问控制权限
                fields[i].setAccessible(accessFlag);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
        }
    }


    public static ZdhInfo create_zhdInfo(TaskLogInstance tli, QuartzJobMapper quartzJobMapper,
                                         EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlMoreTaskMapper etlMoreTaskMapper) throws Exception {

        JSONObject json = new JSONObject();
        String date = DateUtil.formatTime(tli.getCur_time());
        json.put("ETL_DATE", date);
        logger.info(" JOB ,单源,处理当前日期,传递参数ETL_DATE 为" + date);
        tli.setParams(json.toJSONString());

        String etl_task_id = tli.getEtl_task_id();
        //获取etl 任务信息
        EtlTaskInfo etlTaskInfo = etlTaskService.selectById(etl_task_id);
        if (etlTaskInfo == null) {
            logger.info("无法找到对应的[单源]ETL任务,任务id:" + etl_task_id);
            throw new Exception("无法找到对应的[单源]ETL任务,任务id:" + etl_task_id);
        }

        Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
        //此处做参数匹配转换
        if (map != null) {
            logger.info("单源,自定义参数不为空,开始替换:" + tli.getParams());
            //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
            DynamicParams(map, tli, etlTaskInfo, null, null, null);
        }

        //获取数据源信息
        String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
        String data_sources_choose_output = etlTaskInfo.getData_sources_choose_output();
        DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
        if(dataSourcesInfoInput==null){
            logger.info("[单源任务]无法找到对应的[输入]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
            throw new Exception("[单源任务]无法找到对应的[输入]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
        }
        DataSourcesInfo dataSourcesInfoOutput = null;
        if (data_sources_choose_output!=null && !data_sources_choose_output.equals("")) {
            dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
        }
        if(dataSourcesInfoOutput==null){
            logger.info("[单源任务]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
            throw new Exception("[单源任务]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
        }

        if (dataSourcesInfoInput.getData_source_type()!=null && dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
            //获取文件服务器信息 配置到数据源选项
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
            if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
            }
        }

        if (dataSourcesInfoOutput.getData_source_type()!=null && dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
            //获取文件服务器信息 配置到数据源选项
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
            if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                if (etlTaskInfo.getData_sources_params_output() != null && !etlTaskInfo.getData_sources_params_output().trim().equals("")) {
                    JSONObject jsonObject = JSON.parseObject(etlTaskInfo.getData_sources_params_output());
                    jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                } else {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                }
            } else {
                if (etlTaskInfo.getData_sources_params_output() != null && !etlTaskInfo.getData_sources_params_output().trim().equals("")) {
                    JSONObject jsonObject = JSON.parseObject(etlTaskInfo.getData_sources_params_output());
                    jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                } else {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                    etlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                }
            }
        }

        ZdhInfo zdhInfo = new ZdhInfo();
        zdhInfo.setZdhInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, tli);

        return zdhInfo;

    }

    public static ZdhMoreInfo create_more_task_zdhInfo(TaskLogInstance tli, QuartzJobMapper quartzJobMapper,
                                                       EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlMoreTaskMapper etlMoreTaskMapper) throws Exception {
        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(tli.getCur_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,多源,处理当前日期,传递参数ETL_DATE 为" + date);
            tli.setParams(json.toJSONString());

            String etl_task_id = tli.getEtl_task_id();

            //获取多源任务id
            EtlMoreTaskInfo etlMoreTaskInfo = etlMoreTaskMapper.selectByPrimaryKey(etl_task_id);

            //解析多源任务中的单任务
            String[] etl_ids = etlMoreTaskInfo.getEtl_ids().split(",");
            //获取etl 任务信息
            List<EtlTaskInfo> etlTaskInfos = etlTaskService.selectByIds(etl_ids);

            ZdhMoreInfo zdhMoreInfo = new ZdhMoreInfo();
            zdhMoreInfo.setEtlMoreTaskInfo(etlMoreTaskInfo);
            //获取最终输出数据源
            String data_sources_choose_output = etlMoreTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
            if(dataSourcesInfoOutput==null){
                logger.info("[多源任务]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                JobCommon2.insertLog(tli,"WARN","[多源任务]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                dataSourcesInfoOutput=new DataSourcesInfo();
            }

            if (dataSourcesInfoOutput.getData_source_type()!=null && dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (etlMoreTaskInfo.getData_sources_params_output() != null && !etlMoreTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlMoreTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (etlMoreTaskInfo.getData_sources_params_output() != null && !etlMoreTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlMoreTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlMoreTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }


            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
            //此处做参数匹配转换
            for (EtlTaskInfo etlTaskInfo : etlTaskInfos) {
                if (map != null) {
                    logger.info("多源,自定义参数不为空,开始替换:" + tli.getParams());
                    DynamicParams(map, tli, etlTaskInfo, null, null, null);
                }

                //获取数据源信息
                String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                if(dataSourcesInfoInput==null){
                    logger.info("[多源任务]无法找到对应的[输入]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
                    JobCommon2.insertLog(tli,"ERROR","[多源任务]无法找到对应的[输入]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
                    throw new Exception("[多源任务]无法找到对应的[输入]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
                }
                if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                    //获取文件服务器信息 配置到数据源选项
                    ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                    if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                        dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                        dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                        dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                    }
                }


                zdhMoreInfo.setZdhMoreInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, tli, etlMoreTaskInfo);
            }


            return zdhMoreInfo;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static ZdhSqlInfo create_zhdSqlInfo(TaskLogInstance tli, QuartzJobMapper quartzJobMapper,
                                               SqlTaskMapper sqlTaskMapper, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper) throws Exception {

        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(tli.getCur_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,SQL,处理当前日期,传递参数ETL_DATE 为" + date);
            tli.setParams(json.toJSONString());

            String etl_task_id = tli.getEtl_task_id();
            //获取etl 任务信息
            SqlTaskInfo sqlTaskInfo = sqlTaskMapper.selectByPrimaryKey(etl_task_id);

            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
            //此处做参数匹配转换
            if (map != null) {
                logger.info("SQL,自定义参数不为空,开始替换:" + tli.getParams());
                //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
                DynamicParams(map, tli, null, sqlTaskInfo, null, null);
            }

            //获取数据源信息
            String data_sources_choose_input = sqlTaskInfo.getData_sources_choose_input();
            String data_sources_choose_output = sqlTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoInput = new DataSourcesInfo();

            DataSourcesInfo dataSourcesInfoOutput = new DataSourcesInfo();
            if (data_sources_choose_output != null && !data_sources_choose_output.equalsIgnoreCase("")) {
                dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
            }

            if(dataSourcesInfoOutput==null){
                logger.info("[SQL]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                JobCommon2.insertLog(tli,"WARN","[SQL]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                //throw new Exception("[SQL]无法找到对应的[输出]数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
            }

            if (dataSourcesInfoOutput.getData_source_type()!=null && dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (sqlTaskInfo.getData_sources_params_output() != null && !sqlTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(sqlTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (sqlTaskInfo.getData_sources_params_output() != null && !sqlTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(sqlTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        sqlTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }

            ZdhSqlInfo zdhSqlInfo = new ZdhSqlInfo();
            zdhSqlInfo.setZdhInfo(dataSourcesInfoInput, sqlTaskInfo, dataSourcesInfoOutput, tli);

            return zdhSqlInfo;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }

    public static ZdhSshInfo create_zhdSshInfo(TaskLogInstance tli, QuartzJobMapper quartzJobMapper,
                                               SshTaskMapper sshTaskMapper, ZdhNginxMapper zdhNginxMapper) {

        try {

            JarFileMapper jarFileMapper = (JarFileMapper) SpringContext.getBean("jarFileMapper");
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(tli.getCur_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,外部JAR,处理当前日期,传递参数ETL_DATE 为" + date);
            tli.setParams(json.toJSONString());

            String etl_task_id = tli.getEtl_task_id();
            //获取etl 任务信息
            SshTaskInfo sshTaskInfo = sshTaskMapper.selectByPrimaryKey(etl_task_id);

            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
            //此处做参数匹配转换
            if (map != null) {
                logger.info("JAR,自定义参数不为空,开始替换:" + tli.getParams());
                //System.out.println("自定义参数不为空,开始替换:" + dti.getParams());
                DynamicParams(map, tli, null, null, null, sshTaskInfo);
            }
            ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(sshTaskInfo.getOwner());
            List<JarFileInfo> jarFileInfos = jarFileMapper.selectByParams2(sshTaskInfo.getOwner(), new String[]{sshTaskInfo.getId()});

            ZdhSshInfo zdhSshInfo = new ZdhSshInfo();
            zdhSshInfo.setZdhInfo(sshTaskInfo, tli, zdhNginx, jarFileInfos);

            return zdhSshInfo;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }


    public static ZdhDroolsInfo create_zdhDroolsInfo(TaskLogInstance tli, QuartzJobMapper quartzJobMapper,
                                                     EtlTaskService etlTaskService, DataSourcesServiceImpl dataSourcesServiceImpl, ZdhNginxMapper zdhNginxMapper, EtlDroolsTaskMapper etlDroolsTaskMapper,
                                                     EtlMoreTaskMapper etlMoreTaskMapper, SqlTaskMapper sqlTaskMapper) throws Exception {
        try {
            JSONObject json = new JSONObject();
            String date = DateUtil.formatTime(tli.getCur_time());
            json.put("ETL_DATE", date);
            logger.info(" JOB ,Drools,处理当前日期,传递参数ETL_DATE 为" + date);
            tli.setParams(json.toJSONString());

            String etl_task_id = tli.getEtl_task_id();

            //获取Drools任务id
            EtlDroolsTaskInfo etlDroolsTaskInfo = etlDroolsTaskMapper.selectByPrimaryKey(etl_task_id);

            ZdhDroolsInfo zdhDroolsInfo = new ZdhDroolsInfo();

            //获取最终输出数据源
            String data_sources_choose_output = etlDroolsTaskInfo.getData_sources_choose_output();
            DataSourcesInfo dataSourcesInfoOutput = dataSourcesServiceImpl.selectById(data_sources_choose_output);
            if(dataSourcesInfoOutput==null){
                logger.info("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                throw new Exception("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
            }
            if (dataSourcesInfoOutput.getData_source_type()!=null && dataSourcesInfoOutput.getData_source_type().equals("外部下载")) {
                //获取文件服务器信息 配置到数据源选项
                ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoOutput.getOwner());
                if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                    dataSourcesInfoOutput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                    dataSourcesInfoOutput.setUsername(zdhNginx.getUsername());
                    dataSourcesInfoOutput.setPassword(zdhNginx.getPassword());
                    if (etlDroolsTaskInfo.getData_sources_params_output() != null && !etlDroolsTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlDroolsTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getNginx_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                } else {
                    if (etlDroolsTaskInfo.getData_sources_params_output() != null && !etlDroolsTaskInfo.getData_sources_params_output().trim().equals("")) {
                        JSONObject jsonObject = JSON.parseObject(etlDroolsTaskInfo.getData_sources_params_output());
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    } else {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("root_path", zdhNginx.getTmp_dir() + "/" + getUser().getId());
                        etlDroolsTaskInfo.setData_sources_params_output(JSON.toJSONString(jsonObject));
                    }
                }
            }


            if (etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("单源ETL")) {
                //解析Drools任务中的单任务
                String etl_id = etlDroolsTaskInfo.getEtl_id();
                //获取etl 任务信息
                EtlTaskInfo etlTaskInfo = etlTaskService.selectById(etl_id);

                zdhDroolsInfo.setEtlDroolsTaskInfo(etlDroolsTaskInfo);

                Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
                //此处做参数匹配转换

                if (map != null) {
                    logger.info("多源,自定义参数不为空,开始替换:" + tli.getParams());
                    DynamicParams(map, tli, etlTaskInfo, null, null, null);
                }

                //获取数据源信息
                String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                if(dataSourcesInfoInput==null){
                    logger.info("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
                    throw new Exception("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_input);
                }
                if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                    //获取文件服务器信息 配置到数据源选项
                    ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                    if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                        dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                        dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                        dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                    }
                }
                zdhDroolsInfo.setZdhDroolsInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, tli, etlDroolsTaskInfo);
            }

            if (etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("多源ETL")) {
                //获取多源任务id
                EtlMoreTaskInfo etlMoreTaskInfo = etlMoreTaskMapper.selectByPrimaryKey(etlDroolsTaskInfo.getEtl_id());
                zdhDroolsInfo.setEtlMoreTaskInfo(etlMoreTaskInfo);

                //解析多源任务中的单任务
                String[] etl_ids = etlMoreTaskInfo.getEtl_ids().split(",");
                //获取etl 任务信息
                List<EtlTaskInfo> etlTaskInfos = etlTaskService.selectByIds(etl_ids);

                for (EtlTaskInfo etlTaskInfo : etlTaskInfos) {
                    Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
                    //此处做参数匹配转换
                    if (map != null) {
                        logger.info("多源,自定义参数不为空,开始替换:" + tli.getParams());
                        DynamicParams(map, tli, etlTaskInfo, null, null, null);
                    }

                    //获取数据源信息
                    String data_sources_choose_input = etlTaskInfo.getData_sources_choose_input();
                    DataSourcesInfo dataSourcesInfoInput = dataSourcesServiceImpl.selectById(data_sources_choose_input);
                    if(dataSourcesInfoOutput==null){
                        logger.info("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                        throw new Exception("无法找到对应的数据源,任务id:" + etl_task_id+",数据源id:"+data_sources_choose_output);
                    }


                    if (dataSourcesInfoInput.getData_source_type().equals("外部上传")) {
                        //获取文件服务器信息 配置到数据源选项
                        ZdhNginx zdhNginx = zdhNginxMapper.selectByOwner(dataSourcesInfoInput.getOwner());
                        if (zdhNginx != null && !zdhNginx.getHost().equals("")) {
                            dataSourcesInfoInput.setUrl(zdhNginx.getHost() + ":" + zdhNginx.getPort());
                            dataSourcesInfoInput.setUsername(zdhNginx.getUsername());
                            dataSourcesInfoInput.setPassword(zdhNginx.getPassword());
                        }
                    }
                    zdhDroolsInfo.setZdhDroolsInfo(dataSourcesInfoInput, etlTaskInfo, dataSourcesInfoOutput, tli, etlDroolsTaskInfo);
                }

            }
            if (etlDroolsTaskInfo.getMore_task().equalsIgnoreCase("SQL")) {

                //获取etl 任务信息
                SqlTaskInfo sqlTaskInfo = sqlTaskMapper.selectByPrimaryKey(etlDroolsTaskInfo.getEtl_id());

                if (sqlTaskInfo == null) {
                    throw new Exception("无法找到对应的SQL任务");
                }

                Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());
                //此处做参数匹配转换
                if (map != null) {
                    logger.info("SQL,自定义参数不为空,开始替换:" + tli.getParams());
                    DynamicParams(map, tli, null, sqlTaskInfo, null, null);
                }

                zdhDroolsInfo.setSqlTaskInfo(sqlTaskInfo);
                zdhDroolsInfo.setTli(tli);
            }

            //设置drool 任务信息
            zdhDroolsInfo.setEtlDroolsTaskInfo(etlDroolsTaskInfo);
            //设置drool 输出数据源信息
            zdhDroolsInfo.setDsi_Info(dataSourcesInfoOutput);


            return zdhDroolsInfo;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * 根据时间(timestamp) 生成jinjava 模板中的时间参数
     * @param tli
     * @return
     */
    public static Map<String,Object> getJinJavaParam(TaskLogInstance tli){
        String msg="目前支持日期参数以下模式: {{zdh_date}} => yyyy-MM-dd ,{{zdh_date_nodash}}=> yyyyMMdd " +
                ",{{zdh_date_time}}=> yyyy-MM-dd HH:mm:ss,{{zdh_year}}=> 年,{{zdh_month}}=> 月,{{zdh_day}}=> 日," +
                "{{zdh_hour}}=>24小时制,{{zdh_minute}}=>分钟,{{zdh_second}}=>秒,{{zdh_time}}=>时间戳";
        logger.info(msg);
        insertLog(tli, "info", msg);
        Timestamp cur_time=tli.getCur_time();
        String date_nodash = DateUtil.formatNodash(cur_time);
        String date_time = DateUtil.formatTime(cur_time);
        String date_dt = DateUtil.format(cur_time);
        Map<String, Object> jinJavaParam = new HashMap<>();
        jinJavaParam.put("zdh_date_nodash", date_nodash);
        jinJavaParam.put("zdh_date_time", date_time);
        jinJavaParam.put("zdh_date", date_dt);
        jinJavaParam.put("zdh_year",DateUtil.year(cur_time));
        jinJavaParam.put("zdh_month",DateUtil.month(cur_time));
        jinJavaParam.put("zdh_day",DateUtil.day(cur_time));
        jinJavaParam.put("zdh_hour",DateUtil.hour(cur_time));
        jinJavaParam.put("zdh_minute",DateUtil.minute(cur_time));
        jinJavaParam.put("zdh_second",DateUtil.second(cur_time));
        jinJavaParam.put("zdh_time",cur_time.getTime());

        return jinJavaParam;

    }

    public static void DynamicParams(Map<String, Object> map, TaskLogInstance tli, EtlTaskInfo etlTaskInfo, SqlTaskInfo sqlTaskInfo, JarTaskInfo jarTaskInfo, SshTaskInfo sshTaskInfo) {
        try {
            Map<String, Object> jinJavaParam = getJinJavaParam(tli);

            Jinjava jj = new Jinjava();

            map.forEach((k, v) -> {
                logger.info("key:" + k + ",value:" + v);
                jinJavaParam.put(k, v);
            });

            if (etlTaskInfo != null) {
                final String filter = jj.render(etlTaskInfo.getData_sources_filter_input(), jinJavaParam);
                final String clear = jj.render(etlTaskInfo.getData_sources_clear_output(), jinJavaParam);
                final String file_name=jj.render(etlTaskInfo.getData_sources_file_name_input(),jinJavaParam);
                final String file_name2=jj.render(etlTaskInfo.getData_sources_file_name_output(),jinJavaParam);
                final String table_name=jj.render(etlTaskInfo.getData_sources_table_name_input(),jinJavaParam);
                final String table_name2=jj.render(etlTaskInfo.getData_sources_table_name_output(),jinJavaParam);
                etlTaskInfo.setData_sources_filter_input(filter);
                etlTaskInfo.setData_sources_clear_output(clear);
                etlTaskInfo.setData_sources_file_name_input(file_name);
                etlTaskInfo.setData_sources_file_name_output(file_name2);
                etlTaskInfo.setData_sources_table_name_input(table_name);
                etlTaskInfo.setData_sources_table_name_output(table_name2);
            }

            if (sqlTaskInfo != null) {
                final String etl_sql = jj.render(sqlTaskInfo.getEtl_sql(), jinJavaParam);
                final String clear = jj.render(sqlTaskInfo.getData_sources_clear_output(), jinJavaParam);
                final String file_name=jj.render(sqlTaskInfo.getData_sources_file_name_output(),jinJavaParam);
                final String table_name=jj.render(sqlTaskInfo.getData_sources_table_name_output(),jinJavaParam);

                sqlTaskInfo.setEtl_sql(etl_sql);
                sqlTaskInfo.setData_sources_clear_output(clear);
                sqlTaskInfo.setData_sources_file_name_output(file_name);
                sqlTaskInfo.setData_sources_table_name_output(table_name);

            }

            if (jarTaskInfo != null) {
                final String spark_submit_params = jj.render(jarTaskInfo.getSpark_submit_params(), jinJavaParam);
                jarTaskInfo.setSpark_submit_params(spark_submit_params);
            }

            if (sshTaskInfo != null) {
                final String script_path = jj.render(sshTaskInfo.getSsh_script_path(), jinJavaParam);
                sshTaskInfo.setSsh_script_path(script_path);

                jinJavaParam.put("zdh_online_file", sshTaskInfo.getSsh_script_path() + "/" + tli.getId() + "_online");
                final String ssh_cmd = jj.render(sshTaskInfo.getSsh_cmd(), jinJavaParam);
                sshTaskInfo.setSsh_cmd(ssh_cmd);

                final String script_context = jj.render(sshTaskInfo.getSsh_script_context(), jinJavaParam);
                sshTaskInfo.setSsh_script_context(script_context);

            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }


    }


    public static String DynamicParams(Map<String, Object> map, TaskLogInstance tli, String old_str) {
        try {
            Map<String, Object> jinJavaParam = getJinJavaParam(tli);

            Jinjava jj = new Jinjava();

            if(map!=null){
                map.forEach((k, v) -> {
                    logger.info("key:" + k + ",value:" + v);
                    jinJavaParam.put(k, v);
                });
            }

            String new_str=jj.render(old_str, jinJavaParam);

            return new_str;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }


    }

    /**
     * 获取后台url
     *
     * @param zdhHaInfoMapper
     * @return
     */
    public static ZdhHaInfo getZdhUrl(ZdhHaInfoMapper zdhHaInfoMapper,String params) {
        logger.info("获取后台处理URL");
        String url = "http://127.0.0.1:60001/api/v1/zdh";
        String zdh_instance=null;
        if(!StringUtils.isEmpty(params)){
            zdh_instance=JSON.parseObject(params).getString("zdh_instance");
        }

        List<ZdhHaInfo> zdhHaInfoList = zdhHaInfoMapper.selectByStatus("enabled",zdh_instance);
        String id = "-1";
        if (zdhHaInfoList != null && zdhHaInfoList.size() >= 1) {
            int random = new Random().nextInt(zdhHaInfoList.size());
            return zdhHaInfoList.get(random);
        }
        ZdhHaInfo zdhHaInfo = new ZdhHaInfo();
        zdhHaInfo.setId(id);
        zdhHaInfo.setZdh_url(url);
        return zdhHaInfo;
    }

    public static void insertLog(String job_id,String task_logs_id, String level, String msg) {

        ZdhLogs zdhLogs = new ZdhLogs();
        zdhLogs.setJob_id(job_id);
        Timestamp lon_time = new Timestamp(new Date().getTime());
        zdhLogs.setTask_logs_id(task_logs_id);
        zdhLogs.setLog_time(lon_time);
        zdhLogs.setMsg(msg);
        zdhLogs.setLevel(level.toUpperCase());
        //linkedBlockingDeque.add(zdhLogs);
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        zdhLogsService.insert(zdhLogs);
    }

    /**
     * 插入日志
     *
     * @param tli
     * @param level
     * @param msg
     */
    public static void insertLog(TaskLogInstance tli, String level, String msg) {

        ZdhLogs zdhLogs = new ZdhLogs();
        zdhLogs.setJob_id(tli.getJob_id());
        Timestamp lon_time = new Timestamp(new Date().getTime());
        zdhLogs.setTask_logs_id(tli.getId());
        zdhLogs.setLog_time(lon_time);
        zdhLogs.setMsg(msg);
        zdhLogs.setLevel(level.toUpperCase());
        //linkedBlockingDeque.add(zdhLogs);
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        zdhLogsService.insert(zdhLogs);
    }

    public static void insertLog(TaskGroupLogInstance tli, String level, String msg) {

        ZdhLogs zdhLogs = new ZdhLogs();
        zdhLogs.setJob_id(tli.getJob_id());
        Timestamp lon_time = new Timestamp(new Date().getTime());
        zdhLogs.setTask_logs_id(tli.getId());
        zdhLogs.setLog_time(lon_time);
        zdhLogs.setMsg(msg);
        zdhLogs.setLevel(level.toUpperCase());
        //linkedBlockingDeque.add(zdhLogs);
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        zdhLogsService.insert(zdhLogs);
    }


    /**
     * @param task_logs_id
     * @param model_log
     * @param exe_status
     * @param tli
     * @return
     */
    public static Boolean sendZdh(String task_logs_id, String model_log, Boolean exe_status, TaskLogInstance tli) {
        logger.info("开始发送信息到zdh处理引擎");
        QuartzJobMapper quartzJobMapper = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        EtlTaskService etlTaskService = (EtlTaskService) SpringContext.getBean("etlTaskServiceImpl");
        DataSourcesServiceImpl dataSourcesServiceImpl = (DataSourcesServiceImpl) SpringContext.getBean("dataSourcesServiceImpl");
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        ZdhHaInfoMapper zdhHaInfoMapper = (ZdhHaInfoMapper) SpringContext.getBean("zdhHaInfoMapper");
        ZdhNginxMapper zdhNginxMapper = (ZdhNginxMapper) SpringContext.getBean("zdhNginxMapper");
        EtlMoreTaskMapper etlMoreTaskMapper = (EtlMoreTaskMapper) SpringContext.getBean("etlMoreTaskMapper");
        SqlTaskMapper sqlTaskMapper = (SqlTaskMapper) SpringContext.getBean("sqlTaskMapper");
        JarTaskMapper jarTaskMapper = (JarTaskMapper) SpringContext.getBean("jarTaskMapper");
        EtlDroolsTaskMapper etlDroolsTaskMapper = (EtlDroolsTaskMapper) SpringContext.getBean("etlDroolsTaskMapper");
        SshTaskMapper sshTaskMapper = (SshTaskMapper) SpringContext.getBean("sshTaskMapper");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");

        String params = tli.getParams().trim();
        insertLog(tli,"INFO","获取服务端url,指定参数:"+params);
        ZdhHaInfo zdhHaInfo = getZdhUrl(zdhHaInfoMapper,params);
        String url = zdhHaInfo.getZdh_url();
        JSONObject json = new JSONObject();
        if (!params.equals("")) {
            logger.info(model_log + " JOB ,参数不为空判断是否有url 参数");
            String value = JSON.parseObject(params).getString("url");
            if (value != null && !value.equals("")) {
                url = value;
            }
            json = JSON.parseObject(params);
        }
        System.out.println("========fdsfsf=========" + tli.getCur_time());
        String date = DateUtil.formatTime(tli.getCur_time());
        json.put("ETL_DATE", date);
        logger.info(model_log + " JOB ,处理当前日期,传递参数ETL_DATE 为" + date);
        tli.setParams(json.toJSONString());

        logger.info(model_log + " JOB ,获取当前的[url]:" + url);
        JobCommon2.insertLog(tli,"INFO",model_log + " JOB ,获取当前的[url]:" + url);
        ZdhMoreInfo zdhMoreInfo = new ZdhMoreInfo();
        ZdhInfo zdhInfo = new ZdhInfo();
        ZdhSqlInfo zdhSqlInfo = new ZdhSqlInfo();
        ZdhJarInfo zdhJarInfo = new ZdhJarInfo();
        ZdhDroolsInfo zdhDroolsInfo = new ZdhDroolsInfo();
        ZdhSshInfo zdhSshInfo = new ZdhSshInfo();

        try {
            if (tli.getMore_task().equals("多源ETL")) {
                logger.info("组装多源ETL任务信息");
                zdhMoreInfo = create_more_task_zdhInfo(tli, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlMoreTaskMapper);
            } else if (tli.getMore_task().equals("单源ETL")) {
                logger.info("组装单源ETL任务信息");
                zdhInfo = create_zhdInfo(tli, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlMoreTaskMapper);
            } else if (tli.getMore_task().equalsIgnoreCase("SQL")) {
                logger.info("组装SQL任务信息");
                zdhSqlInfo = create_zhdSqlInfo(tli, quartzJobMapper, sqlTaskMapper, dataSourcesServiceImpl, zdhNginxMapper);
            } else if (tli.getMore_task().equalsIgnoreCase("外部JAR")) {
                logger.info("组装外部JAR任务信息");
                //zdhJarInfo = create_zhdJarInfo(tli, quartzJobMapper, jarTaskMapper, zdhNginxMapper);
            } else if (tli.getMore_task().equalsIgnoreCase("Drools")) {
                logger.info("组装Drools任务信息");
                zdhDroolsInfo = create_zdhDroolsInfo(tli, quartzJobMapper, etlTaskService, dataSourcesServiceImpl, zdhNginxMapper, etlDroolsTaskMapper, etlMoreTaskMapper, sqlTaskMapper);
            } else if (tli.getMore_task().equalsIgnoreCase("SSH")) {
                logger.info("组装SSH任务信息");
                zdhSshInfo = create_zhdSshInfo(tli, quartzJobMapper, sshTaskMapper, zdhNginxMapper);
            }

            if (exe_status == true) {
                logger.info(model_log + " JOB ,开始发送ETL处理请求");
                zdhInfo.setTask_logs_id(task_logs_id);
                zdhMoreInfo.setTask_logs_id(task_logs_id);
                zdhSqlInfo.setTask_logs_id(task_logs_id);
                zdhJarInfo.setTask_logs_id(task_logs_id);
                zdhDroolsInfo.setTask_logs_id(task_logs_id);
                zdhSshInfo.setTask_logs_id(task_logs_id);
                insertLog(tli, "DEBUG", "[调度平台]:" + model_log + " JOB ,开始发送ETL处理请求");
                //tli.setEtl_date(date);
                tli.setProcess("10");
                tli.setUpdate_time(new Timestamp(new Date().getTime()));
                updateTaskLog(tli, tlim);
                String executor = zdhHaInfo.getId();
                String url_tmp = "";
                String etl_info = "";
                if (tli.getMore_task().equals("多源ETL")) {
                    url_tmp = url + "/more";
                    etl_info = JSON.toJSONString(zdhMoreInfo);
                } else if (tli.getMore_task().equals("单源ETL")) {
                    url_tmp = url;
                    etl_info = JSON.toJSONString(zdhInfo);
                } else if (tli.getMore_task().equals("SQL")) {
                    url_tmp = url + "/sql";
                    etl_info = JSON.toJSONString(zdhSqlInfo);
                } else if (tli.getMore_task().equals("外部JAR")) {
                    logger.info("[调度平台]:外部JAR,参数:" + JSON.toJSONString(zdhJarInfo));
                    insertLog(tli, "DEBUG", "[调度平台]:外部JAR,参数:" + JSON.toJSONString(zdhJarInfo));
                    // submit_jar(tli, zdhJarInfo);
                } else if (tli.getMore_task().equals("Drools")) {
                    url_tmp = url + "/drools";
                    etl_info = JSON.toJSONString(zdhDroolsInfo);
                } else if (tli.getMore_task().equalsIgnoreCase("SSH")) {
                    etl_info = JSON.toJSONString(zdhSshInfo);
                    logger.info("[调度平台]:SSH,参数:" + JSON.toJSONString(zdhSshInfo));
                    insertLog(tli, "DEBUG", "[调度平台]:SSH,参数:" + JSON.toJSONString(zdhSshInfo));
                    tli.setEtl_info(etl_info);
                    tli.setStatus(JobStatus.ETL.getValue());
                    updateTaskLog(tli, tlim);
                    boolean rs = ssh_exec(tli, zdhSshInfo);
                    if (rs) {
                        tli.setLast_status("finish");
                        //此处是按照同步方式设计的,如果执行的命令是异步命令那些需要用户自己维护这个状态
                        tli.setStatus(JobStatus.FINISH.getValue());
                        tli.setProcess("100");
                        tli.setUpdate_time(new Timestamp(new Date().getTime()));
                        updateTaskLog(tli, tlim);
                    }
                    return rs;
                }

                tli.setExecutor(executor);
                tli.setUrl(url_tmp);
                tli.setEtl_info(etl_info);
                tli.setHistory_server(zdhHaInfo.getHistory_server());
                tli.setMaster(zdhHaInfo.getMaster());
                tli.setApplication_id(zdhHaInfo.getApplication_id());
                tli.setUpdate_time(new Timestamp(new Date().getTime()));
                debugInfo(tli);
                updateTaskLog(tli, tlim);

                // System.exit(-1);

                if (!tli.getMore_task().equalsIgnoreCase("SSH")) {
                    logger.info("[调度平台]:" + url_tmp + " ,参数:" + etl_info);
                    insertLog(tli, "DEBUG", "[调度平台]:" + url_tmp + " ,参数:" + etl_info);
                    HttpUtil.postJSON(url_tmp, etl_info);
                    logger.info(model_log + " JOB ,更新调度任务状态为etl");
                    tli.setLast_status("etl");
                    tli.setStatus(JobStatus.ETL.getValue());
                    tli.setProcess("15");
                    //tlim.updateTaskLogsById3(tli);
                    //updateTaskLog(tli, tlim);
                    tlim.updateStatusById3("etl", "15", tli.getId());
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.info("[调度平台]:" + model_log + " JOB ,开始发送" + tli.getMore_task() + " 处理请求,异常请检查zdh_server服务是否正常运行,或者检查网络情况," + e.getMessage());
            insertLog(tli, "ERROR", "[调度平台]:" + model_log + " JOB ,开始发送" + tli.getMore_task() + " 处理请求,异常请检查zdh_server服务是否正常运行,或者检查网络情况," + e.getMessage());
            tli.setStatus(JobStatus.ERROR.getValue());
            tli.setProcess("17");
            tli.setUpdate_time(new Timestamp(new Date().getTime()));
            updateTaskLog(tli, tlim);
            //更新执行状态为error
            logger.info(model_log + " JOB ,更新调度任务状态为error");
            tli.setLast_status("error");
            logger.error(e.getMessage());
            exe_status = false;
        }

        return exe_status;

    }


    public static boolean ssh_exec(TaskLogInstance tli, ZdhSshInfo zdhSshInfo) throws IOException, JSchException, SftpException {
        try {
            String system = System.getProperty("os.name");
            long t1 = System.currentTimeMillis();
            insertLog(tli, "DEBUG", "[调度平台]:SSH,当前系统为:" + system + ",请耐心等待SSH任务开始执行....");
            String host = zdhSshInfo.getSshTaskInfo().getHost();
            String port = zdhSshInfo.getSshTaskInfo().getPort();
            String username = zdhSshInfo.getSshTaskInfo().getUser_name();
            String password = zdhSshInfo.getSshTaskInfo().getPassword();
            String ssh_cmd = zdhSshInfo.getSshTaskInfo().getSsh_cmd();
            String id = zdhSshInfo.getSshTaskInfo().getId();
            String t_id=zdhSshInfo.getTask_logs_id();
            String script_path = zdhSshInfo.getSshTaskInfo().getSsh_script_path();
            String script_context = zdhSshInfo.getSshTaskInfo().getSsh_script_context();
            List<JarFileInfo> jarFileInfos = zdhSshInfo.getJarFileInfos();
            ZdhNginx zdhNginx = zdhSshInfo.getZdhNginx();
            if (!script_context.isEmpty() || !jarFileInfos.isEmpty()) {
                SFTPUtil sftpUtil = new SFTPUtil(username, password, host, Integer.parseInt(port));
                sftpUtil.login();
                if (!script_context.isEmpty()) {
                    insertLog(tli, "DEBUG", "[调度平台]:SSH,发现在线脚本,使用在线脚本ssh 命令 可配合{{zdh_online_file}} 使用 example sh {{zdh_online_file}} 即是执行在线的脚本");
                    InputStream inputStream = new ByteArrayInputStream(script_context.replaceAll("\r\n","\n").getBytes());
                    sftpUtil.upload(script_path, t_id + "_online", inputStream);
                }

                if (!jarFileInfos.isEmpty()) {
                    for (JarFileInfo jarFileInfo : jarFileInfos) {
                        //下载文件
                        if (zdhNginx.getHost() != null && !zdhNginx.getHost().equals("")) {
                            logger.info("开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                            insertLog(tli, "DEBUG", "[调度平台]:SSH,开始下载文件:SFTP方式" + jarFileInfo.getFile_name());
                            //连接sftp 下载
                            SFTPUtil sftp = new SFTPUtil(zdhNginx.getUsername(), zdhNginx.getPassword(),
                                    zdhNginx.getHost(), new Integer(zdhNginx.getPort()));
                            sftp.login();
                            byte[] fileByte = sftp.download(zdhNginx.getNginx_dir() + "/" + zdhNginx.getOwner(), jarFileInfo.getFile_name());
                            sftpUtil.upload(script_path, jarFileInfo.getFile_name(), fileByte);
                            sftp.logout();
                        } else {
                            logger.info("开始下载文件:本地方式" + jarFileInfo.getFile_name());
                            insertLog(tli, "DEBUG", "[调度平台]:SSH,开始下载文件:本地方式" + jarFileInfo.getFile_name());
                            //本地文件

                            FileInputStream in = null;
                            try {
                                in = new FileInputStream(zdhNginx.getTmp_dir() + "/" + zdhNginx.getOwner() + "/" + jarFileInfo.getFile_name());
                                ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                                System.out.println("bytes available:" + in.available());
                                byte[] temp = new byte[1024];
                                int size = 0;
                                while ((size = in.read(temp)) != -1) {
                                    out.write(temp, 0, size);
                                }
                                byte[] bytes = out.toByteArray();
                                System.out.println("bytes size got is:" + bytes.length);
                                sftpUtil.upload(script_path, jarFileInfo.getFile_name(), in);

                            } catch (Exception e) {
                                e.printStackTrace();
                                throw e;
                            } finally {
                                if (in != null) in.close();
                            }

                        }
                    }
                }

                sftpUtil.logout();
            }

            SSHUtil sshUtil = new SSHUtil(username, password, host, Integer.parseInt(port));
            sshUtil.login();
            chm_ssh.put(tli.getId(),sshUtil);
            insertLog(tli, "DEBUG", "[调度平台]:SSH,使用在线脚本," + ssh_cmd);
            String[] result = sshUtil.exec(ssh_cmd,tli.getId(),tli.getJob_id());
            String error = result[0];
            String out = result[1];
            chm_ssh.get(tli.getId()).logout();
            chm_ssh.remove(tli.getId());
            long t2 = System.currentTimeMillis();

//            for (String li : out.split("\r\n|\n")) {
//                if (!li.trim().isEmpty())
//                    insertLog(tli, "DEBUG", li);
//            }
//            for (String li : error.split("\r\n|\n")) {
//                if (!li.trim().isEmpty())
//                    insertLog(tli, "ERROR", li);
//            }
            insertLog(tli, "DEBUG", "[调度平台]:SSH,SSH任务执行结束,耗时:" + (t2 - t1) / 1000 + "s");

            if (!error.isEmpty()) {
                return false;
            }
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

    }

    public static void updateTaskStatus(String status,String id, String process,TaskLogInstanceMapper tlim) {
        System.out.println("updateTaskLog===============");
        tlim.updateStatusById4(status,process,id);

    }

    public static void updateTaskLog(TaskLogInstance tli, TaskLogInstanceMapper tlim) {
        System.out.println("updateTaskLog===============");
        if(tli.getLast_time()==null){
            if(tli.getCur_time()==null){
                tli.setLast_time(tli.getStart_time());
            }else{
                tli.setLast_time(tli.getCur_time());
            }
        }
        //debugInfo(tli);
        tlim.updateByPrimaryKey(tli);
    }

    public static void updateTaskLog(TaskGroupLogInstance tgli, TaskGroupLogInstanceMapper tglim) {
        System.out.println("updateTaskLog===============");
        if(tgli.getLast_time()==null){
            if(tgli.getCur_time()==null){
                tgli.setLast_time(tgli.getStart_time());
            }else{
                tgli.setLast_time(tgli.getCur_time());
            }
        }
       // debugInfo(tgli);
        tglim.updateByPrimaryKey(tgli);
    }

    /**
     * 更新任务日志,etl_date
     *
     * @param tli
     * @param tlim
     */
    public static void updateTaskLogEtlDate(TaskLogInstance tli, TaskLogInstanceMapper tlim) {
        tli.setUpdate_time(new Timestamp(new Date().getTime()));
        tli.setProcess("6");
        updateTaskLog(tli, tlim);
    }


    public static void updateTaskLogError(TaskLogInstance tli, String process, TaskLogInstanceMapper tlim, String status, int interval_time) {
        tli.setUpdate_time(new Timestamp(new Date().getTime()));
        tli.setStatus(status);//error,retry
        tli.setProcess(process);//调度完成
        tli.setRetry_time(DateUtil.add(new Timestamp(new Date().getTime()), Calendar.SECOND, interval_time));
        updateTaskLog(tli, tlim);
    }

    public static void updateTaskLogError(TaskGroupLogInstance tgli, String process, TaskGroupLogInstanceMapper tglim, String status, int interval_time) {
        tgli.setUpdate_time(new Timestamp(new Date().getTime()));
        tgli.setStatus(status);//error,retry
        tgli.setProcess(process);//调度完成
        tgli.setRetry_time(DateUtil.add(new Timestamp(new Date().getTime()), Calendar.SECOND, interval_time));
        updateTaskLog(tgli, tglim);
    }

    /**
     * 检查子任务依赖
     * 所有的调度之间的依赖关系均由此判断
     * @param jobType
     * @param tli
     */
    public static boolean checkDep(String jobType, TaskLogInstance tli) {
        logger.info("[" + jobType + "] JOB ,开始检查当前任务上游任务依赖");
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,开始检查当前任务上游任务依赖");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        TaskGroupLogInstanceMapper tglim=(TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
        //检查任务依赖
        if (tli.getJump_dep() != null && tli.getJump_dep().equalsIgnoreCase("on")) {
            String msg2 = "[" + jobType + "] JOB ,跳过依赖任务";
            logger.info(msg2);
            insertLog(tli, "INFO", msg2);
            return true;
        }

        if(tli.getPre_tasks()==null || tli.getPre_tasks().equals("") || tli.getPre_tasks().equalsIgnoreCase("root")){
            String msg2 = "[" + jobType + "] JOB ,是根节点任务,无依赖,直接执行";
            logger.info(msg2);
            insertLog(tli, "INFO", msg2);
            return true;
        }else{

            String group_id = tli.getGroup_id();
            TaskGroupLogInstance tgli=tglim.selectByPrimaryKey(group_id);
            String run_jsmind=tgli.getRun_jsmind_data();
            DAG dag=new DAG();
            if ( !StringUtils.isEmpty(run_jsmind)){
                JSONArray json_ary = JSON.parseObject(run_jsmind).getJSONArray("run_line");
                for(int i=0;i<json_ary.size();i++){
                    JSONObject jsonObject=json_ary.getJSONObject(i);
                    dag.addEdge(jsonObject.getString("from"),jsonObject.getString("to"));
                }

                Set<String> parents = dag.getAllParent(tli.getId());
                logger.info("所有的父级,祖先级任务:"+parents);
                insertLog(tli,"INFO","目前只判断父级任务是否完成,如果判断祖先级任务是否完成,可在此处做深度处理...");
                insertLog(tli,"INFO","所有的父级,祖先级任务:"+parents);
            }


            StringBuilder finish_id=new StringBuilder();
            StringBuilder error_id=new StringBuilder();
            StringBuilder kill_id=new StringBuilder();
            StringBuilder run_id=new StringBuilder();
            List<TaskLogInstance> finish=new ArrayList<>();
            List<TaskLogInstance> error=new ArrayList<>();
            List<TaskLogInstance> kill=new ArrayList<>();
            List<TaskLogInstance> run=new ArrayList<>();
            //判断逻辑,级别0：所有的上游任务都执行成功可触发
            //        级别1：所有上游任务执行完成,并存在杀死的任务
            //        级别2：所有上游任务执行完成,并存在失败的任务
            String task_log_ids = tli.getPre_tasks();

            List<TaskLogInstance> taskLogInstances = tlim.selectByIds2(task_log_ids.split(","));
            StringBuilder sb=new StringBuilder();
            for(TaskLogInstance tli_0:taskLogInstances){
                if(tli_0.getStatus().equalsIgnoreCase(JobStatus.FINISH.getValue()) || tli_0.getStatus().equalsIgnoreCase(JobStatus.SKIP.getValue())){
                    finish.add(tli_0);
                    finish_id.append(",");
                    finish_id.append(tli_0.getId());
                }else if(tli_0.getStatus().equalsIgnoreCase(JobStatus.ERROR.getValue())){
                    error.add(tli_0);
                    error_id.append(",");
                    error_id.append(tli_0.getId());
                }else if(tli_0.getStatus().equalsIgnoreCase(JobStatus.KILLED.getValue())){
                    kill.add(tli_0);
                    kill_id.append(",");
                    kill_id.append(tli_0.getId());
                }else{
                    run.add(tli_0);
                    run_id.append(",");
                    run_id.append(tli_0.getId());
                }

            }
            boolean is_pass=true;
            String status="成功";
            if(tli.getDepend_level().equalsIgnoreCase("0")){
                if(run.size()>0 || kill.size()>0 || error.size() > 0){
                    is_pass=false;
                }
            }else if(tli.getDepend_level().equalsIgnoreCase("1")){
                status="杀死";
                if(run.size()>0){
                    is_pass=false;
                }
            }else if(tli.getDepend_level().equalsIgnoreCase("2")){
                status="失败";
                if(run.size()>0){
                    is_pass=false;
                }
            }

            String msg = "[" + jobType + "] JOB ,当前任务依赖上游任务状态:["+status+"],才会触发";
            logger.info(msg);
            insertLog(tli, "INFO", msg);
            String etl_date = tli.getEtl_date();
            String msg2 = "已完成任务:"+(finish_id.toString().startsWith(",")?finish_id.toString().substring(1):finish_id.toString());
            String msg3 = "已杀死任务:"+ (kill_id.toString().startsWith(",")?kill_id.toString().substring(1):kill_id.toString());
            String msg4 = "已失败任务:"+(error_id.toString().startsWith(",")?error_id.toString().substring(1):error_id.toString());
            String msg5 = "未完成任务:"+(run_id.toString().startsWith(",")?run_id.toString().substring(1):run_id.toString());
            logger.info(msg2);
            logger.info(msg3);
            logger.info(msg4);
            logger.info(msg5);
            insertLog(tli, "INFO", msg2);
            insertLog(tli, "INFO", msg3);
            insertLog(tli, "INFO", msg4);
            insertLog(tli, "INFO", msg5);

//            tli.setStatus("check_dep");
//            tli.setProcess("7");
//            tli.setUpdate_time(new Timestamp(new Date().getTime()));
//            updateTaskLog(tli,tlim);
            updateTaskStatus("check_dep",tli.getId(),"7",tlim);

            if(is_pass){
                String msg6 = "[" + jobType + "] JOB ,依赖任务状态,满足当前任务触发条件" +",ETL日期" + etl_date ;
                logger.info(msg6);
                insertLog(tli, "INFO", msg6);
                return true;
            }else{
                String msg6 = "[" + jobType + "] JOB ,依赖任务状态,未满足当前任务触发条件" +",ETL日期" + etl_date ;
                logger.info(msg6);
                insertLog(tli, "INFO", msg6);
                return false;
            }

        }


    }

    /**
     * 检查子任务中的任务组依赖(特殊依赖-group)
     * 可理解为类似数据采集的任务(只是这个任务只做依赖关系)
     * @param jobType
     * @param tli
     * @return
     */
    public static boolean checkDep_group(String jobType,TaskLogInstance tli){
        logger.info("[" + jobType + "] JOB ,开始检查任务依赖");
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,开始检查任务依赖");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        TaskGroupLogInstanceMapper tglim = (TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
        String dep_job_id=tli.getEtl_task_id();
        String etl_date=tli.getEtl_date();

        if(!checkDep(jobType,tli)){
            return false;
        }

        List<TaskGroupLogInstance> taskLogsList = tglim.selectByIdEtlDate(dep_job_id, etl_date);
        if (taskLogsList == null || taskLogsList.size() <= 0) {
            String msg = "[" + jobType + "] JOB ,依赖任务组:" + dep_job_id + ",ETL日期:" + etl_date + ",未完成";
            logger.info(msg);
            insertLog(tli, "INFO", msg);
            tli.setThread_id(""); //设置为空主要是为了 在检查依赖任务期间杀死
            tli.setStatus(JobStatus.CHECK_DEP.getValue());
            tli.setProcess("7");
            tli.setUpdate_time(new Timestamp(new Date().getTime()));
            updateTaskLog(tli,tlim);
            return false;
        }
        String msg2 = "[" + jobType + "] JOB ,依赖任务组:" + dep_job_id + ",ETL日期:" + etl_date + ",已完成";
        logger.info(msg2);
        insertLog(tli, "INFO", msg2);
        return true;
    }



    /**
     * 检查子任务中的jdbc依赖(特殊依赖-jdbc)
     * 可理解为类似数据采集的任务(只是这个任务只做依赖关系)
     * @param jobType
     * @param tli
     * @return
     */
    public static boolean checkDep_jdbc(String jobType,TaskLogInstance tli){
        logger.info("[" + jobType + "] JOB ,开始检查任务中的JDBC依赖,目前jdbc依赖只支持单条sql语句检查");
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,开始检查任务中的JDBC依赖,目前jdbc依赖只支持单条sql语句检查");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        TaskGroupLogInstanceMapper tglim = (TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
        String etl_date=tli.getEtl_date();

        if(!checkDep(jobType,tli)){
            return false;
        }

        DBUtil dbUtil=new DBUtil();

        System.out.println("checkDep_jdbc:"+tli.getRun_jsmind_data());
        if(!com.zyc.zdh.util.StringUtils.isEmpty(tli.getRun_jsmind_data())){
            JSONObject jdbc=JSON.parseObject(tli.getRun_jsmind_data());
            String driver= jdbc.getString("driver");
            String url= jdbc.getString("url");
            String username= jdbc.getString("username");
            String password= jdbc.getString("password");
            String jdbc_sql= jdbc.getString("jdbc_sql");
            Map<String, Object> map = (Map<String, Object>) JSON.parseObject(tli.getParams());

            if(StringUtils.isEmpty(jdbc_sql)){
                insertLog(tli,"INFO","JDBC依赖检查SQL:sql语句为空,直接跳过检查");
                return true;
            }
            //jdbc_sql 动态参数替换
            String new_jdbc_sql=DynamicParams(map,tli,jdbc_sql);


            insertLog(tli,"INFO","JDBC依赖检查SQL: "+new_jdbc_sql);

            if (new_jdbc_sql.trim().toLowerCase().startsWith("insert")){

                String[] rst= dbUtil.CUD(driver,url,username,password,new_jdbc_sql,new String[]{});
                if(rst[0].equalsIgnoreCase("true")){
                    String msg = "[" + jobType + "] JOB ,JDBC写入成功 "+ ",ETL日期:" + etl_date;
                    insertLog(tli,"INFO",msg);
                    return true;
                }
                String msg = "[" + jobType + "] JOB ,JDBC写入异常:" +rst[1]+ ",ETL日期:" + etl_date;
                insertLog(tli,"INFO",msg);
                jobFail(tli.getJob_type(),tli);
                return false;
            }
            try {
               List<Map<String,Object>> result= dbUtil.R5(driver,url,username,password,new_jdbc_sql);
               if(result.size()<1){
                   String msg = "[" + jobType + "] JOB ,依赖JDBC任务检查" + ",ETL日期:" + etl_date + ",未完成";
                   insertLog(tli,"INFO",msg);
                   return false;
               }

            } catch (Exception e) {
                e.printStackTrace();
                insertLog(tli,"error","JDBC依赖检查异常,"+e.getMessage());
                jobFail(tli.getJob_type(),tli);
                return false;
            }

        }else{
            insertLog(tli,"error","请检查jdbc链接需要的基础参数是否缺少");
            jobFail(tli.getJob_type(),tli);
        }

        String msg2 = "[" + jobType + "] JOB ,依赖JDBC任务检查" + ",ETL日期:" + etl_date + ",已完成";
        logger.info(msg2);
        insertLog(tli, "INFO", msg2);
        return true;
    }

    /**
     * 检查hdfs依赖
     * @param jobType
     * @param tli
     * @return
     */
    public static boolean checkDep_hdfs(String jobType,TaskLogInstance tli){
        logger.info("[" + jobType + "] JOB ,开始检查任务中的HDFS依赖,目前HDFS依赖支持检查单个文件和写入单个文件,不支持密码验证");
        insertLog(tli, "INFO", "[" + jobType + "] JOB ,开始检查任务中的HDFS依赖,目前HDFS依赖支持检查单个文件和写入单个文件,不支持密码验证");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        TaskGroupLogInstanceMapper tglim = (TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
        String etl_date=tli.getEtl_date();

        if(!checkDep(jobType,tli)){
            return false;
        }
        Map<String,Object> param=getJinJavaParam(tli);
        Jinjava jj = new Jinjava();
        String jsmin_data=jj.render(tli.getRun_jsmind_data(),param);
        JSONObject jsmin_json=JSON.parseObject(jsmin_data);

        String fs_defaultFS=jsmin_json.getString("url");
        String hadoop_user_name=jsmin_json.getString("username");
        String path_str=jj.render(jsmin_json.getString("hdfs_path"),param);
        String hdfs_mode=jsmin_json.getString("hdfs_mode");
        Configuration conf = new Configuration();
        boolean result=true;
        try {
            logger.info("[" + jobType + "] JOB ,开始连接hadoop,参数url:" + fs_defaultFS + ",用户:" + hadoop_user_name+" ,路径:"+path_str);
            insertLog(tli, "info", "[" + jobType + "] JOB ,开始连接hadoop,参数url:" + fs_defaultFS + ",用户:" + hadoop_user_name+" ,路径:"+path_str);
            FileSystem fs = FileSystem.getLocal(conf);
            if(!StringUtils.isEmpty(fs_defaultFS)){
                fs = FileSystem.get(new URI(fs_defaultFS), conf, hadoop_user_name);
            }

            if(StringUtils.isEmpty(hdfs_mode)){
                throw new Exception("请选择正确的文件模式,检查文件/生成文件");
            }

            if(!StringUtils.isEmpty(hdfs_mode) && hdfs_mode.equalsIgnoreCase("0")){
                insertLog(tli, "info", "[" + jobType + "] JOB , 开始检查文件:"+path_str+" ,是否存在");
                result=fs.exists(new Path(path_str));

            }else if(!StringUtils.isEmpty(hdfs_mode) && hdfs_mode.equalsIgnoreCase("1")){
                insertLog(tli, "info", "[" + jobType + "] JOB , 开始生成文件:"+path_str);
                result=fs.createNewFile(new Path(path_str));
            }
            if(result){
                insertLog(tli, "info", "[" + jobType + "] JOB , 完成检查文件/生成文件:"+path_str);
            }else{
                insertLog(tli, "info", "[" + jobType + "] JOB , 检查文件/生成文件:"+path_str+", 未完成,将再次检查/生成");
            }
            return result;

        } catch (IOException e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            insertLog(tli,"ERROR",e.getMessage());
            JobCommon2.jobFail(jobType,tli);
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            insertLog(tli,"ERROR",e.getMessage());
            JobCommon2.jobFail(jobType,tli);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            logger.error(e.getMessage());
            insertLog(tli,"ERROR",e.getMessage());
        } catch (Exception e){
            e.printStackTrace();
            logger.error(e.getMessage());
            insertLog(tli,"ERROR",e.getMessage());
        }

        JobCommon2.jobFail(jobType,tli);
        return false;
    }

    /**
     * 时间序列发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param tli
     * @return
     */
    public static boolean runTimeSeq(String jobType, String task_logs_id, boolean exe_status, TaskLogInstance tli) {

        logger.info("[" + jobType + "] JOB,任务模式为[时间序列]");
        insertLog(tli, "info", "[" + jobType + "] JOB,任务模式为[时间序列]");

        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(tli, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, tli);

        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(tli, "info", "[" + jobType + "] JOB ,执行命令成功");

            if (tli.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(tli, "info", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                tli.setEnd_time(new Timestamp(new Date().getTime()));
            }

        } else {
            setJobLastStatus(tli, task_logs_id, tlim);
        }
        return exe_status;
    }

    /**
     * 执行一次任务发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param tli
     * @return
     */
    public static boolean runOnce(String jobType, String task_logs_id, boolean exe_status, TaskLogInstance tli) {
        logger.info("[" + jobType + "] JOB,任务模式为[ONCE]");
        insertLog(tli, "info", "[" + jobType + "] JOB,任务模式为[ONCE]");

        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(tli, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, tli);

        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(tli, "info", "[" + jobType + "] JOB ,执行命令成功");

            if (tli.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(tli, "info", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                tli.setEnd_time(new Timestamp(new Date().getTime()));
            }
            tli.setStatus("finish");
            //插入日志
            logger.info("[" + jobType + "] JOB ,结束调度任务");
            insertLog(tli, "info", "[" + jobType + "] JOB ,结束调度任务");

        } else {
            setJobLastStatus(tli, task_logs_id, tlim);
        }
        quartzManager2.deleteTask(tli, "finish", "finish");
        return exe_status;
    }

    /**
     * 重复执行任务发送etl任务到后台执行
     *
     * @param jobType
     * @param task_logs_id
     * @param exe_status
     * @param tli
     * @return
     */
    public static boolean runRepeat(String jobType, String task_logs_id, boolean exe_status, TaskLogInstance tli) {
        logger.info("[" + jobType + "] JOB,任务模式为[重复执行模式]");
        insertLog(tli, "info", "[" + jobType + "] JOB,任务模式为[重复执行模式]");

        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        logger.info("[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        insertLog(tli, "info", "[" + jobType + "] JOB ,调度命令执行成功,准备发往任务到后台ETL执行");
        exe_status = sendZdh(task_logs_id, "[" + jobType + "]", exe_status, tli);
        if (exe_status) {
            logger.info("[" + jobType + "] JOB ,执行命令成功");
            insertLog(tli, "INFO", "[" + jobType + "] JOB ,执行命令成功");

            if (tli.getEnd_time() == null) {
                logger.info("[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                insertLog(tli, "INFO", "[" + jobType + "] JOB ,结束日期为空设置当前日期为结束日期");
                tli.setEnd_time(new Timestamp(new Date().getTime()));
            }
        } else {
            setJobLastStatus(tli, task_logs_id, tlim);
        }
        return exe_status;
    }

    /**
     * 调度执行的任务命令,jdbc,失败后触发此方法
     *
     * @param jobType
     * @param tli
     */
    public static void jobFail(String jobType, TaskLogInstance tli) {
        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        logger.info("[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行");
        insertLog(tli, "info", "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行");
        String msg = "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行,重试次数已达到最大,状态设置为error";
        String status = "error";
        if (tli.getPlan_count().equalsIgnoreCase("-1") || Long.parseLong(tli.getPlan_count()) > tli.getCount()) {
            //重试
            status = "wait_retry";
            msg = "[" + jobType + "] JOB ,调度命令执行失败未能发往任务到后台ETL执行,状态设置为wait_retry等待重试";
            if (tli.getPlan_count().equalsIgnoreCase("-1")) {
                msg = msg + ",并检测到重试次数为无限次";
            }
        }
        logger.info(msg);
        insertLog(tli, "ERROR", msg);
        int interval_time = (tli.getInterval_time() == null || tli.getInterval_time().equals("")) ? 5 : Integer.parseInt(tli.getInterval_time());
        //调度时异常
        updateTaskLogError(tli, "9", tlim, status, interval_time);

//        if (status.equalsIgnoreCase("error")) {
//            quartzManager2.deleteTask(tli, "finish", status);
//        }
    }

    public static void setJobLastStatus(TaskLogInstance tli, String task_logs_id, TaskLogInstanceMapper tlim) {
        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        String status = "error";
        String msg = "发送ETL任务到zdh处理引擎,存在问题,重试次数已达到最大,状态设置为error";
        if (tli.getPlan_count().equalsIgnoreCase("-1") || Long.parseLong(tli.getPlan_count()) > tli.getCount()) {
            //重试
            status = "wait_retry";
            msg = "发送ETL任务到zdh处理引擎,存在问题,状态设置为wait_retry等待重试";
            if (tli.getPlan_count().equalsIgnoreCase("-1")) {
                msg = msg + ",并检测到重试次数为无限次";
            }
        }
        logger.info(msg);
        insertLog(tli, "ERROR", msg);
        int interval_time = (tli.getInterval_time() == null || tli.getInterval_time().equals("")) ? 5 : Integer.parseInt(tli.getInterval_time());
        updateTaskLogError(tli, "17", tlim, status, interval_time);
//        if (status.equalsIgnoreCase("error")) {
//            quartzManager2.deleteTask(tli, "finish", status);
//        }
    }

    public static User getUser() {
        User user = (User) SecurityUtils.getSubject().getPrincipal();
        return user;
    }

    /**
     *  选择具体的job执行引擎,只有调度和手动重试触发(手动执行部分请参见其他方法)
     *  解析任务组时间->解析创建任务组实例->创建子任务实例
     * @param quartzJobInfo
     * @param is_retry      0:调度,2:手动重试
     * @param sub_tasks     重试的子任务divId,如果全部重试可传null,或者所有的divId
     */
    public static void chooseJobBean(QuartzJobInfo quartzJobInfo, int is_retry, TaskGroupLogInstance retry_tgli,String[] sub_tasks) {
        QuartzJobMapper qjm = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        TaskGroupLogInstanceMapper tglim = (TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        //手动重试增加重试实例,自动重试在原来的基础上

        if (quartzJobInfo.getJob_type().equals("EMAIL")) {
            logger.debug("调度任务[EMAIL],开始调度");
            EmailJob.run(quartzJobInfo);
            EmailJob.notice_event();
            return;
        } else if (quartzJobInfo.getJob_type().equals("RETRY")) {
            logger.debug("调度任务[RETRY],开始调度");
            RetryJob.run(quartzJobInfo);
            return;
        } else if (quartzJobInfo.getJob_type().equals("CHECK")) {
            logger.debug("调度任务[CHECK],开始调度");
            CheckDepJob.run(quartzJobInfo);
            return;
        }

        //线程池执行具体调度任务
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {

                TaskGroupLogInstance tgli = new TaskGroupLogInstance();
                tgli.setId(SnowflakeIdWorker.getInstance().nextId() + "");
                try {
                    //复制quartzjobinfo到tli,任务基础信息完成复制
                    BeanUtils.copyProperties(tgli, quartzJobInfo);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
                //逻辑发送错误代码捕获发生自动重试(retry_job) 不重新生成实例id,使用旧的实例id
                String last_task_id="";
                if(is_retry==0){
                    //调度触发,直接获取执行时间
                    Timestamp cur=getCurTime(quartzJobInfo);
                    tgli.setCur_time(cur);
                    tgli.setEtl_date(DateUtil.formatTime(cur));
                    tgli.setRun_time(new Timestamp(new Date().getTime()));//实例开始时间
                    tgli.setUpdate_time(new Timestamp(new Date().getTime()));
                }

                if(is_retry==2){
                    //手动点击重试按钮触发
                    //手动点击重试,会生成新的实例信息,默认重置执行次数,并将上次执行失败的实例id 付给last_task_id
                    tgli=retry_tgli;
                    tgli.setProcess("1");
                    tgli.setRun_time(new Timestamp(new Date().getTime()));//实例开始时间
                    tgli.setUpdate_time(new Timestamp(new Date().getTime()));
                    tgli.setCount(0L);
                    tgli.setIs_retryed("0");
                    tgli.setId(SnowflakeIdWorker.getInstance().nextId() + "");
                    tgli.setStatus(JobStatus.NON.getValue());
                }
                //此处更新主要是为了 日期超时时 也能记录下日志
                if (is_retry == 1) {
                    tglim.updateByPrimaryKey(tgli);
                } else {
                    tglim.insert(tgli);
                }
                qjm.updateByPrimaryKey(quartzJobInfo);
                //公共设置
                tgli.setStatus(JobStatus.NON.getValue());//新实例状态设置为dispatch
                //设置调度器唯一标识,调度故障转移时使用,如果服务器重启会自动生成新的唯一标识
                //tgli.setServer_id(JobCommon2.web_application_id);

                //todo 生成具体任务组下任务实例
                sub_task_log_instance(tgli,sub_tasks);
                tglim.updateStatus2Create(new String[]{tgli.getId()});
                debugInfo(tgli);
                tglim.updateByPrimaryKey(tgli);

                //检查任务依赖,和并行不冲突--此逻辑删除,目前任务组之间的依赖以子任务检查逻辑实现
                //boolean dep = checkDep(quartzJobInfo.getJob_type(), tgli);
                //更新任务依赖时间
                process_time_info pti=tgli.getProcess_time2();
                pti.setCheck_dep_time(DateUtil.getCurrentTime());
                tgli.setProcess_time(pti);
                //修改组任务状态,及修改子任务状态为检查依赖中
                CheckDepJob.updateTaskGroupLogInstanceStatus(tgli);
//                if(dep){
//
//                }else{
//                    updateTaskLog(tgli,tglim);
//                    return ;
//                }
            }
        });

        //如果调度任务类型是一次性则删除调度
        if(quartzJobInfo.getJob_model().equalsIgnoreCase(JobModel.ONCE.getValue())){
            quartzManager2.deleteTask(quartzJobInfo,JobStatus.FINISH.getValue());
        }
    }

    /** 只管QUARTZ调度触发,手动执行另算
     * 如果使用调度时间,直接赋值即可
     * 如果使用自定义时间,获取上次时间和本次做diff
     */
    public static Timestamp getCurTime(QuartzJobInfo quartzJobInfo){
        QuartzManager2 quartzManager2 = (QuartzManager2) SpringContext.getBean("quartzManager2");
        if(quartzJobInfo.getUse_quartz_time() != null && quartzJobInfo.getUse_quartz_time().equalsIgnoreCase("on")){
            if(!StringUtils.isEmpty(quartzJobInfo.getTime_diff())){
                int seconds=0;
                try {
                     seconds = Integer.parseInt(quartzJobInfo.getTime_diff());
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
                return DateUtil.add(quartzJobInfo.getQuartz_time(),Calendar.SECOND,-seconds);
            }
            return quartzJobInfo.getQuartz_time();
        }

        Timestamp cur=null;
        String step_size = quartzJobInfo.getStep_size();
        int dateType = Calendar.DAY_OF_MONTH;
        int num = 1;
        if (step_size.endsWith("s")) {
            dateType = Calendar.SECOND;
            num = Integer.parseInt(step_size.split("s")[0]);
        }
        if (step_size.endsWith("m")) {
            dateType = Calendar.MINUTE;
            num = Integer.parseInt(step_size.split("m")[0]);
        }
        if (step_size.endsWith("h")) {
            dateType = Calendar.HOUR;
            num = Integer.parseInt(step_size.split("h")[0]);
        }
        if (step_size.endsWith("d")) {
            dateType = Calendar.DAY_OF_MONTH;
            num = Integer.parseInt(step_size.split("d")[0]);
        }

        if(quartzJobInfo.getLast_time()==null){
           quartzJobInfo.setLast_time(quartzJobInfo.getStart_time());
           cur=quartzJobInfo.getStart_time();
        }else{
            //此处不适用月因时间无法正常确定,调度中时间按照秒做单位
            cur=DateUtil.add(quartzJobInfo.getLast_time(),dateType,num);
            quartzJobInfo.setLast_time(cur);
        }

        //判断自定义时间是否超过,超过删除删除调度
        Timestamp second=DateUtil.add(quartzJobInfo.getLast_time(),dateType,num);
        if(cur.getTime() > quartzJobInfo.getEnd_time().getTime() || second.getTime() > quartzJobInfo.getEnd_time().getTime()){
            quartzManager2.deleteTask(quartzJobInfo,"finish");
        }

        return cur;


    }


    /**
     * 任务触发后,等待依赖任务完成触发,其他外部均调用此方法执行子任务实例
     * @param tli
     */
    public static void chooseJobBean(TaskLogInstance tli) {
        QuartzJobMapper qjm = (QuartzJobMapper) SpringContext.getBean("quartzJobMapper");
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");

        //线程池执行具体调度任务
        //threadPoolExecutor.execute();
        new Thread(new Runnable() {
            @Override
            public void run() {
                run_sub_task_log_instance(tli.getJob_type(),tli);
            }
        }).start();
    }


    /**
     * 公共执行子任务入口,只能由chooseJobBean 调用
     * @param jobType
     * @param tli
     */
    private static void run_sub_task_log_instance(String jobType, TaskLogInstance tli) {
        TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        logger.info("开始执行[" + jobType + "] JOB");
        insertLog(tli, "INFO", "开始执行[" + jobType + "] JOB");

        Thread td=Thread.currentThread();
        td.setName(tli.getId());
        long threadId = td.getId();
        System.out.println("线程id:"+threadId);
        String tk=myid+"_"+threadId+"_"+tli.getId();
        JobCommon2.chm.put(tk,td);

        tlim.updateThreadById(tk,tli.getId());
        tli.setThread_id(tk);
        try{
            boolean end = isCount(jobType, tli);
            if (end == true) return;
            Boolean exe_status = true;
            if(jobType.equalsIgnoreCase("ETL")){
                //拼接任务信息发送请求
                if (tli.getJob_model().equals(JobModel.TIME_SEQ.getValue())) {
                    runTimeSeq(jobType, tli.getId(), exe_status, tli);
                } else if (tli.getJob_model().equals(JobModel.ONCE.getValue())) {
                    runOnce(jobType, tli.getId(), exe_status, tli);
                } else if (tli.getJob_model().equals(JobModel.REPEAT.getValue())) {
                    runRepeat(jobType, tli.getId(), exe_status, tli);
                }
                tli.setProcess_time("");
                updateTaskLog(tli,tlim);
            }else if(jobType.equalsIgnoreCase("SHELL")){
                exe_status=ShellJob.shellCommand(tli);
                if(exe_status){
                    //设置任务状态为finish
                    tli.setStatus(JobStatus.FINISH.getValue());
                    tli.setProcess("100");
                    updateTaskLog(tli,tlim);
                }
            }

        }catch (Exception e){
            e.printStackTrace();
        }finally {
            JobCommon2.chm.remove(tk);
        }

    }


    public static List<Date> resolveQuartzExpr(String expr) throws ParseException {

        List<Date> dates = new ArrayList<>();

        if(expr.endsWith("s") || expr.endsWith("m") || expr.endsWith("h") || expr.endsWith("d")){
            long time = Integer.valueOf(expr.substring(0, expr.length() - 1));

            int dateType = Calendar.DAY_OF_MONTH;
            int num = 1;
            if (expr.endsWith("s")) {
                dateType = Calendar.SECOND;
                num = Integer.parseInt(expr.split("s")[0]);
            }
            if (expr.endsWith("m")) {
                dateType = Calendar.MINUTE;
                num = Integer.parseInt(expr.split("m")[0]);
            }
            if (expr.endsWith("h")) {
                dateType = Calendar.HOUR;
                num = Integer.parseInt(expr.split("h")[0]);
            }
            if (expr.endsWith("d")) {
                dateType = Calendar.DAY_OF_MONTH;
                num = Integer.parseInt(expr.split("d")[0]);
            }

            for(int i=1;i<=10;i++){
                dates.add(DateUtil.add(new Timestamp(new Date().getTime()),dateType,num*i));
            }

        }else{
            CronTriggerImpl cronTriggerImpl = new CronTriggerImpl();
            cronTriggerImpl.setCronExpression(expr);//这里写要准备猜测的cron表达式
            dates=TriggerUtils.computeFireTimes(cronTriggerImpl, null, 10);
        }

        return dates;

    }

    public static List<Date> resolveQuartzExpr(String use_quartz_time,String step_size,String expr,String start_time,String end_time) throws ParseException {

        List<Date> dates1 = new ArrayList<>();
        List<Date> dates = new ArrayList<>();
        //对于自定义时间
        if(expr.endsWith("s") || expr.endsWith("m") || expr.endsWith("h")|| expr.endsWith("d")){
            long time = Integer.valueOf(expr.substring(0, expr.length() - 1));

            int dateType = Calendar.DAY_OF_MONTH;
            int num = 1;
            if (expr.endsWith("s")) {
                dateType = Calendar.SECOND;
                num = Integer.parseInt(expr.split("s")[0]);
            }
            if (expr.endsWith("m")) {
                dateType = Calendar.MINUTE;
                num = Integer.parseInt(expr.split("m")[0]);
            }
            if (expr.endsWith("h")) {
                dateType = Calendar.HOUR;
                num = Integer.parseInt(expr.split("h")[0]);
            }
            if (expr.endsWith("d")) {
                dateType = Calendar.DAY_OF_MONTH;
                num = Integer.parseInt(expr.split("d")[0]);
            }

            boolean if_end=true;
            Timestamp st=new Timestamp(DateUtil.pase(start_time,"yyyy-MM-dd HH:mm:ss").getTime());
            Timestamp end=new Timestamp(DateUtil.pase(end_time,"yyyy-MM-dd HH:mm:ss").getTime());
            dates.add(st);
            while(if_end){
                st=DateUtil.add(st,dateType,num);
                if(st.getTime()<=end.getTime()){
                    dates.add(st);
                }else{
                    if_end=false;
                }
            }
        }else{
            CronTriggerImpl cronTriggerImpl = new CronTriggerImpl();
            cronTriggerImpl.setCronExpression(expr);//这里写要准备猜测的cron表达式
            dates=TriggerUtils.computeFireTimesBetween(cronTriggerImpl,null, DateUtil.pase(start_time,"yyyy-MM-dd HH:mm:ss"),DateUtil.pase(end_time,"yyyy-MM-dd HH:mm:ss"));
        }

        if(use_quartz_time.equalsIgnoreCase("on")){
            return dates;
        }


        int num = 0;
        int dateType=Calendar.DAY_OF_MONTH;
        if (step_size.endsWith("s")) {
            dateType = Calendar.SECOND;
            num = Integer.parseInt(step_size.split("s")[0]);
        }
        if (step_size.endsWith("m")) {
            dateType = Calendar.MINUTE;
            num = Integer.parseInt(step_size.split("m")[0]);
        }
        if (step_size.endsWith("h")) {
            dateType = Calendar.HOUR;
            num = Integer.parseInt(step_size.split("h")[0]);
        }
        if (step_size.endsWith("d")) {
            dateType = Calendar.DAY_OF_MONTH;
            num = Integer.parseInt(step_size.split("d")[0]);
        }

        Timestamp tmp=Timestamp.valueOf(start_time);
        dates1.add(tmp);
        for(int i=0;i<dates.size();i++){
            tmp=DateUtil.add(tmp,dateType,num);
            if(tmp.getTime()<=Timestamp.valueOf(end_time).getTime()){
                dates1.add(tmp);
            }else{
                break;
            }
        }

        return dates1;

    }

    /**
     * 使用quartz 触发时间时,获取指定时间差的etl_date
     * @param cur
     * @param step_size
     * @return
     */
    public static Timestamp getEtlDate(Timestamp cur,String step_size){
        int dateType = Calendar.SECOND;
        int num = 0;
        if (step_size.endsWith("s")) {
            dateType = Calendar.SECOND;
            num = Integer.parseInt(step_size.split("s")[0]);
        }
        if (step_size.endsWith("m")) {
            dateType = Calendar.MINUTE;
            num = Integer.parseInt(step_size.split("m")[0]);
        }
        if (step_size.endsWith("h")) {
            dateType = Calendar.HOUR;
            num = Integer.parseInt(step_size.split("h")[0]);
        }
        if (step_size.endsWith("d")) {
            dateType = Calendar.DAY_OF_MONTH;
            num = Integer.parseInt(step_size.split("d")[0]);
        }

        return DateUtil.add(cur,dateType,num);
    }

    /**
     * 根据tli 任务组生成具体的子任务实例
     * @param tgli
     * @param sub_tasks 具体执行的子任务divId
     */
    public static List<TaskLogInstance> sub_task_log_instance(TaskGroupLogInstance tgli,String[] sub_tasks){
        List<TaskLogInstance> tliList=new ArrayList<>();
        try{
            TaskGroupLogInstanceMapper tglim = (TaskGroupLogInstanceMapper) SpringContext.getBean("taskGroupLogInstanceMapper");
            TaskLogInstanceMapper tlim = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
            // divId和实例id映射
            Map<String,String> map=new HashMap<>(); //divId->id
            Map<String,String> map2=new HashMap<>();//id->divId
            DAG dag=new DAG();
            JSONArray tasks=JSON.parseObject(tgli.getJsmind_data()).getJSONArray("tasks");
           //JSONArray shell=JSON.parseObject(tgli.getJsmind_data()).getJSONArray("shell");
            JSONArray lines=JSON.parseObject(tgli.getJsmind_data()).getJSONArray("line");
            for(Object job :tasks){
                TaskLogInstance taskLogInstance=new TaskLogInstance();
                BeanUtils.copyProperties(taskLogInstance,tgli);

                String etl_task_id=((JSONObject) job).getString("etl_task_id");//具体任务id
                String pageSourceId=((JSONObject) job).getString("divId");//前端生成的div 标识
                String more_task=((JSONObject) job).getString("more_task");
                String is_disenable=((JSONObject) job).getString("is_disenable");
                String depend_level=((JSONObject) job).getString("depend_level");
                if(com.zyc.zdh.util.StringUtils.isEmpty(is_disenable)){
                    is_disenable="false";
                }
                if(com.zyc.zdh.util.StringUtils.isEmpty(depend_level)){
                    depend_level="0";
                }

                String etl_context=((JSONObject) job).getString("etl_context");
                String command=((JSONObject) job).getString("command");//具体任务id
                String is_script=((JSONObject) job).getString("is_script");//具体任务id

                if(((JSONObject) job).getString("type").equalsIgnoreCase("tasks")){
                    taskLogInstance.setMore_task(more_task);
                    taskLogInstance.setJob_type("ETL");
                    String zdh_instance=((JSONObject) job).getString("zdh_instance");
                    if( !com.zyc.zdh.util.StringUtils.isEmpty(zdh_instance) && com.zyc.zdh.util.StringUtils.isEmpty(taskLogInstance.getParams())){
                        JSONObject jsonObject=new JSONObject();
                        jsonObject.put("zdh_instance",zdh_instance);
                        taskLogInstance.setParams(jsonObject.toJSONString());
                    }

                    if( !com.zyc.zdh.util.StringUtils.isEmpty(zdh_instance) && !com.zyc.zdh.util.StringUtils.isEmpty(taskLogInstance.getParams())){
                        JSONObject jsonObject=JSON.parseObject(taskLogInstance.getParams());
                        jsonObject.put("zdh_instance",zdh_instance);
                        taskLogInstance.setParams(jsonObject.toJSONString());
                    }

                }
                if(((JSONObject) job).getString("type").equalsIgnoreCase("shell")){
                    taskLogInstance.setMore_task("");
                    taskLogInstance.setJob_type("SHELL");
                }
                if(((JSONObject) job).getString("type").equalsIgnoreCase("group")){
                    taskLogInstance.setMore_task("");
                    taskLogInstance.setJob_type("GROUP");
                }

                taskLogInstance.setJsmind_data("");
                taskLogInstance.setRun_jsmind_data("");
                if(((JSONObject) job).getString("type").equalsIgnoreCase("jdbc")){
                    taskLogInstance.setJsmind_data(((JSONObject) job).toJSONString());
                    taskLogInstance.setRun_jsmind_data(((JSONObject) job).toJSONString());
                    taskLogInstance.setMore_task("");
                    taskLogInstance.setJob_type("JDBC");
                }

                if(((JSONObject) job).getString("type").equalsIgnoreCase("hdfs")){
                    taskLogInstance.setJsmind_data(((JSONObject) job).toJSONString());
                    taskLogInstance.setRun_jsmind_data(((JSONObject) job).toJSONString());
                    taskLogInstance.setMore_task("");
                    taskLogInstance.setJob_type("HDFS");
                }


                String t_id=SnowflakeIdWorker.getInstance().nextId()+"";
                map.put(pageSourceId,t_id);//div标识和任务实例id 对应关系
                map2.put(t_id,pageSourceId);

                taskLogInstance.setId(t_id);//具体执行任务实例id,每次执行都会重新生成
                taskLogInstance.setJob_id(tgli.getJob_id());//调度任务id
                taskLogInstance.setJob_context(tgli.getJob_context());//调度任务说明
                taskLogInstance.setEtl_task_id(etl_task_id);//etl任务id
                taskLogInstance.setEtl_context(etl_context);//etl任务说明
                taskLogInstance.setCommand(command);
                taskLogInstance.setIs_script(is_script);

                taskLogInstance.setStatus(JobStatus.NON.getValue());


                taskLogInstance.setCount(0);
                taskLogInstance.setOwner(tgli.getOwner());
                taskLogInstance.setIs_disenable(is_disenable);
                taskLogInstance.setDepend_level(depend_level);
                tliList.add(taskLogInstance);
            }

            JSONArray jary_line=new JSONArray();
            for(Object job :lines){
                String pageSourceId=((JSONObject) job).getString("pageSourceId");
                String pageTargetId=((JSONObject) job).getString("pageTargetId");
                ((JSONObject) job).put("id",map.get(pageSourceId));
                ((JSONObject) job).put("parentid",map.get(pageTargetId));
                if(pageSourceId !=null && !pageSourceId.equalsIgnoreCase("root")){
                    dag.addEdge(map.get(pageSourceId),map.get(pageTargetId));//此处的依赖关系 都是生成的任务实例id
                    JSONObject json_line=new JSONObject();
                    json_line.put("from",map.get(pageSourceId));
                    json_line.put("to",map.get(pageTargetId));
                    jary_line.add(json_line);
                }
            }

            //run_data 结构：run_data:[{task_log_instance_id,etl_task_id,etl_context,more_task}]
            JSONArray jary=new JSONArray();
            for(TaskLogInstance tli:tliList){
                JSONObject jsonObject1=new JSONObject();
                String tid=tli.getId();
                System.out.println("=======================");

                jsonObject1.put("task_log_instance_id",tid);
                jsonObject1.put("etl_task_id",tli.getEtl_task_id());
                jsonObject1.put("etl_context",tli.getEtl_context());
                jsonObject1.put("more_task",tli.getMore_task());
                jsonObject1.put("job_type",tli.getJob_type());
                jsonObject1.put("divId",map2.get(tid));
                jary.add(jsonObject1);
            }

            JSONObject jsonObject=JSON.parseObject(tgli.getJsmind_data());
            jsonObject.put("run_data",jary);
            jsonObject.put("run_line",jary_line);
            tgli.setRun_jsmind_data(jsonObject.toJSONString());
            tgli.setProcess("6.5");
            tglim.updateByPrimaryKey(tgli);
            //debugInfo(tgli);


            //生成实例
            for(TaskLogInstance tli:tliList){
                String tid=tli.getId();
                String next_tasks=StringUtils.join(dag.getChildren(tid).toArray(),",");
                String pre_tasks=StringUtils.join(dag.getParent(tid),",");
                tli.setGroup_id(tgli.getId());
                tli.setGroup_context(tgli.getJob_context());
                tli.setNext_tasks(next_tasks);
                tli.setPre_tasks(pre_tasks);
                if(sub_tasks==null){
                    tli.setStatus(JobStatus.NON.getValue());
                }else{
                    //不再sub_tasks 中的div 状态设置为跳过状态
                    if(Arrays.asList(sub_tasks).contains(map2.get(tid)) && !tli.getIs_disenable().equalsIgnoreCase("true")){
                        //设置为初始态
                        tli.setStatus(JobStatus.NON.getValue());
                    }else{
                        tli.setStatus(JobStatus.SKIP.getValue());
                        tli.setProcess("100");
                    }
                }


                tlim.insert(tli);
               // debugInfo(tli);
                System.out.println("=======================");
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return tliList;
    }
}
