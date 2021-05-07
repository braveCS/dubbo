

隐式: 节点名称common-conf
    >和env平级的公共节点：
    >和applicationName平级的公共节点

3配置文件加载逻辑： 应用下的子节点 不加载
  默认识别出tomcat上下文路径，优先在/evn/application/上下文路径   这个节点加载配置
  如果不存在，就在/evn/application 这个节点加载配置


 显式:  @import=/xxx/yyy,/zzz

==========================================================================================
3. 公共节点和应用配置节点 配置的 覆盖优先级：公共<应用节点<公共!important<应用节点!important

4. 占位符， 插值 ${env, system:} 类似log4j2 加密   ENC()

5. 开发框架辅助业务逻辑:
dubbo.protocol.contextpath  xx/dubbo
dubbo.protocol.port


5. 验证功能global.txt















































