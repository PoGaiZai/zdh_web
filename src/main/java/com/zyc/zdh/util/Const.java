package com.zyc.zdh.util;

public class Const {

    public static String TRUR="true";
    public static String FALSE="false";

    public static String ON="on";
    public static String OFF="off";

    public static String STATUS_PUB="1";//发布
    public static String STATUS_NOT_PUB="2";//未发布

    public static String SHOW="1";
    public static String NOT_SHOW="0";

    public static String END="1";
    public static String NOT_END="0";

    //流程状态,0:未审批,1:审批完成,2:不通过,3:撤销
    public static String STATUS_INIT="0";
    public static String STATUS_SUCCESS="1";
    public static String STATUS_FAIL="2";
    public static String STATUS_RECALL="3";//啊方法的身份

    public static String DELETE="1";
    public static String NOT_DELETE="0";

    public static String SMS_INIT = "0";//未处理
    public static String SMS_HANDLING = "1";//处理中
    public static String SMS_FAIL = "2";//处理失败
    public static String SMS_SUCCESS = "3";//成功
    public static String SMS_NOT_HANDLE = "4";//不做处理

    public static String BATCH_INIT = "0";//未执行
    public static String BATCH_RUNNING = "1";//执行中
    public static String BATCH_FAIL = "2";//执行失败
    public static String BATCH_SUCCESS = "3";//执行成功

}