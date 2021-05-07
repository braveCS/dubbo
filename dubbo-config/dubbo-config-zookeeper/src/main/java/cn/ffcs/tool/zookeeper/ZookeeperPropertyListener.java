package cn.ffcs.tool.zookeeper;

import org.apache.logging.log4j.core.lookup.Interpolator;
import org.apache.logging.log4j.core.lookup.MapLookup;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.ContextLoaderListener;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.Query;
import javax.servlet.ServletContextEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <pre>
 * 	1) 完全覆盖本地global.properties；
 * 	2) 增加公共配置节点和子节点：
 *  	配置读取顺序：公共节点>应用节点>应用子节点。后者覆盖前者。公共节点和应用子节点可以有多个
 *   	公共节点位置：应用节点同级
 *   	公共节点名称：默认common-conf，可在setenv.sh文件里使用-DcommonConf=xxx,yyy来指定公共配置节点，逗号分隔多个节点名
 *   	应用子节点位置：应用节点下一级
 *   	应用子节点名称：没有限制，一般遵循文件命名规则
 * </pre>
 */
public class ZookeeperPropertyListener extends ContextLoaderListener {
    private static Logger logger = LoggerFactory.getLogger(ZookeeperPropertyListener.class);
    private static final String COMMON_CONF = "commonConf";
    public static final String COMMON_CONF_DEFAULT = "common-conf";
    private static final String ENVIRONMENT_NAME = "environmentName";
    private static final String APPLICATION_NAME = "applicationName";
    private static final String ZOOKEEPER_URL = "zookeeperUrl";
    private static final String PROPERTIES_FILE = "global.properties";
    private static final String CONFIG_ITEM_FILE = "global.txt";

    private String environmentName;
    private String applicationName;
    private String zookeeperUrl;
    private List<String> commonConfPathList = new ArrayList<>();

    private ZooKeeper zk;
    private Properties properties = new Properties();
    private String contextPath;


    @Override
    public void contextInitialized(ServletContextEvent event) {

        if (StringUtils.isEmpty(zookeeperUrl)) {
            zookeeperUrl = getConfigParameter(ZOOKEEPER_URL, null);
        }
        if (StringUtils.isEmpty(environmentName)) {
            environmentName = getConfigParameter(ENVIRONMENT_NAME, null);
        }
        if (StringUtils.isEmpty(applicationName)) {
            applicationName = getConfigParameter(APPLICATION_NAME, null);
        }

        contextPath = event.getServletContext().getContextPath();
        if (!StringUtils.isEmpty(contextPath)) {
            contextPath = contextPath.substring(1);
        }

        if (StringUtils.isEmpty(applicationName)) {
            applicationName = contextPath;
        }

        if (StringUtils.isEmpty(applicationName) || StringUtils.isEmpty(environmentName) || StringUtils.isEmpty(zookeeperUrl)) {
            megerLocalProperties();
            logger.info("使用本地配置文件global.properties");
            doContextInitialized(event);
            return;
        }

        try {
            connectToZK();

            if (CollectionUtils.isEmpty(commonConfPathList)) {
                commonConfPathList = Arrays.asList(getConfigParameter(COMMON_CONF, COMMON_CONF_DEFAULT).split(","));
            }
            ZKPropertiesLoader zkPropertiesLoader = new ZKPropertiesLoader(zk, environmentName, applicationName, contextPath, commonConfPathList);
            properties = zkPropertiesLoader.loadProperties();

            overWriterGlobalProperties();
            //megerLocalProperties(properties);
            logger.info("zookeeper上的配置合并到本地的配置文件global.properties");
        } finally {
            try {
                zk.close();
            } catch (InterruptedException e) {
                logger.error("关闭ZooKeeper服务器  {} 的链接出现如下异常", e);
            }
        }
        doContextInitialized(event);
    }


    /**
     * 写入到global.properties文件里
     */
    private void overWriterGlobalProperties() {
        BufferedWriter bw = null;
        try {
            //加载global.properties
            File file = getResourceAsFile(PROPERTIES_FILE);
            logger.info("重写的文件：{}", file);
            if (!file.exists()) {
                logger.info("{}global.properties", file.createNewFile() ? "创建" : "修改");
            }

            //一些额外的属性
            appendSomeProcessProperties();

            //4. 占位符， 插值 ${env, system:} 类似log4j2 加密   ENC()
            parseProperties();

            //保存
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
            properties.store(bw, null);
        } catch (IOException e) {
            logger.error("覆盖global.prorperties出错", e);
        } finally {
            IOUtils.closeStream(bw);
        }
    }

    /*** 合并本地的global.properties并保存，并增加一些额外的参数*/
    private void megerLocalProperties() {

        BufferedReader br = null;
        OutputStreamWriter osw = null;
        try {
            //加载global.properties
            File file = getResourceAsFile(PROPERTIES_FILE);
            logger.info("合并本地的文件：{}", file);

            if (!file.exists())
                logger.info("{}global.properties", file.createNewFile() ? "创建" : "修改");
            else {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                properties.load(br);
            }

            appendSomeProcessProperties();

            //4. 占位符， 插值 ${env, system:} 类似log4j2 加密   ENC()
            parseProperties();

            //保存
            osw = new OutputStreamWriter(new FileOutputStream(file));
            properties.store(osw, "合并后的properties");
        } catch (IOException e) {
            logger.error("合并global.prorperties出错", e);
            IOUtils.closeStream(osw);
            IOUtils.closeStream(br);
        }
    }

    /**
     * <pre>设置dubbo.registry.dev.subscribe和dubbo.registry.dev.register
     * 如果是生产环境，则严格设置为false，
     * 否者，如果事前没有特别设置，则设置为true </pre>
     */
    private void appendSomeProcessProperties() {
        String register = "dubbo.registry.dev.register";
        String subscribe = "dubbo.registry.dev.subscribe";
        String devProduct = properties.getProperty("dubbo.zookeeper.id");
        String devSubscribe = properties.getProperty(subscribe);
        String devRegister = properties.getProperty(register);
        String resolveFile = properties.getProperty("dubbo.resolve.file");

        if ("product".equals(devProduct)) {
            properties.setProperty(subscribe, "false");
            properties.setProperty(register, "false");
        } else if (resolveFile != null && resolveFile.endsWith(".properties")) {  //判断是否走直连
            System.setProperty("dubbo.resolve.file", resolveFile);
            properties.setProperty(subscribe, "true");  //消费者如果直连配置文件里有配置就不会去注册了，没有配置的还会走注册
            properties.setProperty(register, "false");
        } else {
            if (devSubscribe == null || devSubscribe.length() == 0)
                properties.setProperty(subscribe, "true");
            if (devRegister == null || devRegister.length() == 0)
                properties.setProperty(register, "true");
        }

        //去掉前后空格和/t
        //1.不间断空格\u00A0,主要用在office中,让一个单词在结尾处不会换行显示,快捷键ctrl+shift+space ;
        //2.半角空格(英文符号)\u0020,代码中常用的;
        //3.全角空格(中文符号)\u3000,中文文章中使用;
        String value;
        String patternString="[\\p{C}|\\u00A0-\\u00FF|\\u3000|\\s]*";
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            value = (String) entry.getValue();
            value = value.replaceAll("^"+patternString,"").replaceAll(patternString+"$","");
            properties.put(entry.getKey(), value);
        }

        //设置spring的profile
        String springProfile = properties.getProperty("spring.profiles.active");
        if (springProfile != null && springProfile.trim().length() > 0)
            System.setProperty("spring.profiles.active", springProfile.trim());

        //设置默认的元数据中心
        String metadataAddr = properties.getProperty("dubbo.zookeeper.address.metadata");
        if (metadataAddr == null && zookeeperUrl != null) {
            properties.put("dubbo.zookeeper.address.metadata", "zookeeper://" + zookeeperUrl);
        }

        //识别tomcat上下文，自动补全contextpath
        String protoclContextpath = properties.getProperty("dubbo.protocol.contextpath");
        if(!StringUtils.isEmpty(protoclContextpath)){
            if (!StringUtils.isEmpty(contextPath)&&!protoclContextpath.startsWith(contextPath)) {
                properties.put("dubbo.protocol.contextpath", contextPath + "/" + StringUtils.trimLeadingCharacter(protoclContextpath, '/'));
            }else if(contextPath.length()==0&&protoclContextpath.contains("/")){ //上下文为""，需要纠正
                properties.put("dubbo.protocol.contextpath",protoclContextpath.substring(protoclContextpath.indexOf("/")+1));
            }
        }


        //自动识别端口，并重写端口配置信息
        String port = properties.getProperty("dubbo.protocol.port");
        String tomcatPort=getTomcatPort();
        //如果找到tomcat端口
        if(!StringUtils.isEmpty(tomcatPort)){
            if(StringUtils.isEmpty(port)){
                properties.put("dubbo.protocol.port",tomcatPort);
            }else if(!port.equalsIgnoreCase(tomcatPort)){
                String forcePort = properties.getProperty("dubbo.protocol.port.force");
                if(StringUtils.isEmpty(forcePort)){
                    logger.error("================== 配置端口（{}）和tomcat实际端口（{}）不一致，启动失败：" +
                            "请修改dubbo.protocol.port={},或直接删除dubbo.protocol.port这个配置============",port,tomcatPort,tomcatPort);
                    System.exit(1);
                }else{
                    properties.put("dubbo.protocol.port.force",forcePort);
                }

            }
        }
    }

    private String getTomcatPort(){
        try {
            MBeanServer server = null;
            if (!CollectionUtils.isEmpty(MBeanServerFactory.findMBeanServer(null))) {
                server = MBeanServerFactory.findMBeanServer(null).get(0);
            }

            if (server != null) {
                Set names = server.queryNames(new ObjectName("Catalina:type=Connector,*"),
                        Query.match(Query.attr("protocol"), Query.value("HTTP/1.1")));

                Iterator iterator = names.iterator();
                if (iterator.hasNext()) {
                    ObjectName name = (ObjectName) iterator.next();
                    return server.getAttribute(name, "port").toString();
                }
            }
        } catch (Exception e) {
            logger.debug("可能不是tomcat所以暂时不能自动获取到端口");
        }
        return null;
    }

    //=======================================================================================================


    /**
     * 链接zk， 阻塞的，不成功返回null
     */
    private void connectToZK() {
        if (zookeeperUrl == null || zookeeperUrl.length() == 0)
            return;

        String usernamePwd=null;
        String realUrl=zookeeperUrl;
        int upIndex=zookeeperUrl.lastIndexOf('@');
        if(upIndex!=-1){
            usernamePwd = zookeeperUrl.substring(0,upIndex);
             realUrl=zookeeperUrl.substring(upIndex+1);
        }

        //准备链接zk
        CountDownLatch connectedLatch = new CountDownLatch(1);
        Watcher watcher = new ConnectedWatcher(connectedLatch);
        try {
            zk = new ZooKeeper(realUrl, 60000, watcher);
            if(!StringUtils.isEmpty(usernamePwd)){
                zk.addAuthInfo("digest", usernamePwd.getBytes());
            }
            waitUntilConnected(zk, connectedLatch);
            logger.info("与ZooKeeper服务器{} 连接成功 ", zookeeperUrl);
        } catch (IOException e) {
            logger.info("与ZooKeeper服务器 {} 连接失败 ", zookeeperUrl);
        }
    }

    private static void waitUntilConnected(ZooKeeper zooKeeper, CountDownLatch connectedLatch) {
        if (States.CONNECTING == zooKeeper.getState()) {
            try {
                connectedLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * 至此配置参数个数是完整的，剩下：验证&启动初始化
     */
    private void doContextInitialized(ServletContextEvent event) {
        //5. 验证功能global.txt
        checkConfigItems();

        //6. 启动初始化
        super.contextInitialized(event);
    }


    /**
     * 占位符， 插值 ${env, system:} 类似log4j2 加密   ENC()
     */
    private void parseProperties() {

        Map<String, String> paramMap = new HashMap<>();
        Set<String> keys = properties.stringPropertyNames();

        //第一轮替换，基本替换
        StrSubstitutor log4jSubstitutor = new StrSubstitutor(new Interpolator(paramMap));
        substitutorProperties(keys, paramMap, log4jSubstitutor);

        //第二轮替换，解密
        Interpolator encInterpolator = new Interpolator(new MapLookup(paramMap), Collections.singletonList("cn.ffcs.tool.zookeeper.util"));
        StrSubstitutor encSubstitutor = new StrSubstitutor(encInterpolator);
        substitutorProperties(keys, paramMap, encSubstitutor);

        //第二轮替换，完整
        substitutorProperties(keys, paramMap, log4jSubstitutor);
    }

    private void substitutorProperties(Set<String> keys, Map<String, String> paramMap, StrSubstitutor substitutor) {
        Set<String> needParseKeys = new HashSet<>();
        keys.forEach(key -> {
            String value = properties.getProperty(key);
            if (!value.contains("${")) {
                paramMap.put(key, value);
            } else {
                needParseKeys.add(key);
            }
        });

        needParseKeys.forEach(key -> {
            String value = properties.getProperty(key);
            if (value.contains("${")) {
                value = substitutor.replace(value);
                properties.put(key, value);
                paramMap.put(key, value);
            }
        });
    }


    /**
     * 校验配置项是否完整，配置项放在classpath:global.txt,用回车分隔配置项
     */
    private void checkConfigItems() {
        if (properties == null)
            return;

        List<String> miss = new ArrayList<>();
        Properties configProp = new Properties();
        try {
            File file = getResourceAsFile(CONFIG_ITEM_FILE);
            logger.info("检验的配置文件：{}", file);
            if (!file.exists())
                return;

            FileReader fr = new FileReader(file);
            configProp.load(fr);
            Enumeration<Object> configKeys = configProp.keys();
            String key;
            while (configKeys.hasMoreElements()) {
                key = (String) configKeys.nextElement();
                if (!properties.containsKey(key))
                    miss.add(key);
            }
            fr.close();
        } catch (IOException e) {
            logger.warn("IOException#########################{}", e.getMessage());
        }

        if (CollectionUtils.isEmpty(miss)) {
            return;
        }

        for (String item : miss) {
            logger.error("=========  没有配置，提示：请配置(等号后面是示例值)：{}={}", item, configProp.getProperty(item));
        }
        System.exit(1);
    }

    /**
     * 获取参数值，查找顺序：环境变量，虚拟机变量，默认值
     */
    private String getConfigParameter(String key, String defaultValue) {
        String res;
        res = System.getenv(key);
        if (res == null || res.length() == 0) {
            res = System.getProperty(key);
            if (res == null || res.length() == 0)
                res = defaultValue;
        }
        return res;
    }

    private File getResourceAsFile(String resource) {
        return new File(Thread.currentThread().getContextClassLoader().getResource("").getPath() + resource);
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public void setEnvironmentName(String environmentName) {
        this.environmentName = environmentName;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getZookeeperUrl() {
        return zookeeperUrl;
    }

    public void setZookeeperUrl(String zookeeperUrl) {
        this.zookeeperUrl = zookeeperUrl;
    }

    public List<String> getCommonConfPathList() {
        return commonConfPathList;
    }

    public void setCommonConfPathList(List<String> commonConfPathList) {
        this.commonConfPathList = commonConfPathList;
    }

}
