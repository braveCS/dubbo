package cn.ffcs.tool.zookeeper;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 2 隐式: 节点名称common-conf
 * >和env平级的公共节点：
 * >和applicationName平级的公共节点
 * <p>
 * 3配置文件加载逻辑： 应用下的子节点 不加载
 * 默认识别出tomcat上下文路径，优先在/evn/application/上下文路径   这个节点加载配置
 * 如果不存在，就在/evn/application 这个节点加载配置
 * <p>
 * 显式:  @import=/xxx/yyy,/zzz
 * <p>
 * 3. 公共节点和应用配置节点 配置的 覆盖优先级：公共<应用节点<公共!important<应用节点!important
 */
public class ZKPropertiesLoader {
    private static Logger logger = LoggerFactory.getLogger(ZKPropertiesLoader.class);
    public static final String SYNTAX_IMPORT = "@import";
    public static final String SYNTAX_IMPORTANT = "!important";

    private ZooKeeper zk;
    private String environmentName;
    private String applicationName;
    private String contextPath;
    private List<String> commonConfPathList = new ArrayList<>();
    private Properties commonProperties = new Properties();
    private Properties appProperties = new Properties();

    public ZKPropertiesLoader(ZooKeeper zk, String environmentName, String applicationName, String contextPath, List<String> commonConfPathList) {
        this.zk = zk;
        this.environmentName = environmentName;
        this.applicationName = applicationName;
        this.contextPath = contextPath;
        this.commonConfPathList = commonConfPathList;
    }

    public Properties loadProperties() {
        loadCommonProperties();
        loadAppProperties();
        propertiesOverwrite();
        return appProperties;
    }

    /**
     * 隐式: 节点名称common-conf
     */
    private void loadCommonProperties() {
        //和env平级的公共节点：
        commonConfPathList.forEach(node -> addZKNodeProperties("/" + node, commonProperties));
        //>和applicationName平级的公共节点
        commonConfPathList.forEach(node -> addZKNodeProperties("/" + environmentName + "/" + node, commonProperties));
    }


    private void loadAppProperties() {

        //默认识别出tomcat上下文路径，优先在/evn/application/上下文路径   这个节点加载配置
        if(!StringUtils.isEmpty(contextPath)) {
            addZKNodeProperties("/" + environmentName + "/" + applicationName + "/" + contextPath, appProperties);

            if(appProperties.size()==0){
                addZKNodeProperties("/" + environmentName + "/" + contextPath, appProperties);
            }
        }

        //如果不存在，就在/evn/application 这个节点加载配置
        if(appProperties.size()==0){
            addZKNodeProperties("/" + environmentName + "/" + applicationName, appProperties);
        }

    }


    /*3. 公共节点和应用配置节点 配置的 覆盖优先级：公共<应用节点<公共!important<应用节点!important*/
    private void propertiesOverwrite() {

        //2种情况：
        commonProperties.stringPropertyNames().forEach(key->{
            String commonVal=StringUtils.trimWhitespace(commonProperties.getProperty(key));
            String appVal=StringUtils.trimWhitespace(appProperties.getProperty(key));
            if(StringUtils.isEmpty(commonVal)){
                return;
            }

            //公共存在， 应用不存在
            if(StringUtils.isEmpty(appVal)){
                appProperties.put(key,commonVal);
                return;
            }

            //应用节点<公共!important
            int commonIndex= commonVal.lastIndexOf(SYNTAX_IMPORTANT);
            int appIndex = appVal.lastIndexOf(SYNTAX_IMPORTANT);
            if(commonIndex!=-1&&appIndex==-1){
                appProperties.put(key,commonVal);
            }
        });

        //最后给appProperties里的!important去掉
        appProperties.stringPropertyNames().forEach(key->{
            String value=appProperties.getProperty(key);
            int importantIndex=value.lastIndexOf(SYNTAX_IMPORTANT);
            if(importantIndex!=-1){
                appProperties.put(key,value.substring(0,importantIndex));
            }
        });
    }


    /**
     * 加载zk上指定节点的properties配置
     */
    private void addZKNodeProperties(String path, Properties properties) {
        String content;
        try {
            content = new String(zk.getData(path, false, null), StandardCharsets.UTF_8);
            addProperty(path, content, properties);

            //解析import: 显式:  @import=/xxx/yyy,/zzz
            if (properties.containsKey(SYNTAX_IMPORT)) {
                String[] importPaths = properties.getProperty(SYNTAX_IMPORT).split(",");
                properties.remove(SYNTAX_IMPORT);
                Arrays.stream(importPaths).forEach(importPath -> addZKNodeProperties(importPath, properties));
            }
        } catch (KeeperException e) {
            logger.info("{}:获取zookeeper配置时不存在:{}", path, path);
        } catch (InterruptedException e) {
            logger.error(e.toString(), e);
        }
    }

    private void addProperty(String nodeName, String value, Properties properties) {
        if (value == null || value.length() == 0) {
            logger.info("节点{}没有配置信息", nodeName);
            return;
        }
        try {
            logger.info("节点{}的配置信息是:{}", nodeName, value);
            if (value.indexOf('=') > 1)
                properties.load(new StringReader(value));
            else
                properties.put(StringUtils.getFilename(nodeName), value);
        } catch (IOException ex) {
            logger.error("加载节点上的配置信息为properties失败");
        }
    }

}
