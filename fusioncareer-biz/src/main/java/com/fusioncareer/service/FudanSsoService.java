package com.fusioncareer.service;

public interface FudanSsoService {

    String createState();

    void verifyState(String readExpected, String readActual);

    /**
     * 构建复旦 SSO 统一认证的跳转地址
     *
     * @return SSO 登录页面地址
     */
    String buildLoginUrl(String readState);

    /**
     * 处理复旦 SSO 回调，进行系统内自动注册与登录
     *
     * @param code SSO 返回的授权码
     * @return 系统内登录成功后的前端跳转地址
     */
    String processCallback(String code);

    /**
     * 主动发起退出：注销本地会话并构建 Fudan 统一退出地址
     *
     * @return SSO 注销页面地址
     */
    String processLogout();

    /**
     * 被动跟随退出：处理 Fudan 认证中心后端通知应用注销会话
     *
     * @param token Fudan 返回的 access_token
     */
    void processSlo(String token);

}
