package cn.ffcs.tool.zookeeper;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.common.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.ContextLoaderListener;

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
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

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
    private static final String COMMON_CONF_DEFAULT = "common-conf";
    private static final String ENVIRONMENT_NAME = "environmentName";
    private static final String APPLICATION_NAME = "applicationName";
    private static final String ZOOKEEPER_URL = "zookeeperUrl";
    private static final String PROPERTIES_FILE = "global.properties";
    private static final String CONFIG_ITEM_FILE = "global.txt";

    private String environmentName;
    private String applicationName;
    private String zookeeperUrl;
    private List<String> commonConfPathList = new ArrayList<String>();

    private ZooKeeper zk;
    private Properties properties = new Properties();


    private void doContextInitialized(ServletContextEvent event){
        checkConfigItems();
        /*try {
            SysVM.init();
        } catch (Exception e) {
            logger.error("证书校验失败",e);
            System.exit(1);
            return;
        }*/
        super.contextInitialized(event);
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        if (environmentName == null)
            environmentName = getConfigParameter(ENVIRONMENT_NAME, null);
        if (applicationName == null)
            applicationName = getConfigParameter(APPLICATION_NAME, null);
        if (zookeeperUrl == null)
            zookeeperUrl = getConfigParameter(ZOOKEEPER_URL, null);


        if (applicationName == null || applicationName.length() == 0
                || environmentName == null || environmentName.length() == 0
                || zookeeperUrl == null || zookeeperUrl.length() == 0) {
            megerLocalProperties();
            logger.info("使用本地配置文件global.properties");
            doContextInitialized(event);
            return;
        }

        try {
            connectToZK();

            //0)获取公共配置节点的数据
            initCommonConfPaths();
            for (String commonNode : commonConfPathList)
                addZKNodeProperties("/" + environmentName + "/" + commonNode);

            //1)获取指定节点的数据,读取本级节点的配置信息
            addZKNodeProperties("/" + environmentName + "/" + applicationName);

            overWriterGlobalProperties();
            //megerLocalProperties(properties);
            logger.info("zookeeper上的配置合并到本地的配置文件global.properties");
            zk.close();
        } catch (InterruptedException e) {
            logger.error("关闭ZooKeeper服务器  {} 的链接出现如下异常", e);
        }
        doContextInitialized(event);
    }

    /**
     * 校验配置项是否完整，配置项放在classpath:global.txt,用回车分隔配置项
     */
    private void checkConfigItems() {
        if (properties == null)
            return;

        List<String> miss = new ArrayList<String>();
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

        if (CollectionUtils.isEmpty(miss))
            return;
        for (String item : miss)
            logger.error("没有配置：{}={} ", item, configProp.getProperty(item));
        System.exit(1);
    }

    private void addZKNodeProperties(String path) {
        String content;
        try {
            content = new String(zk.getData(path, false, null), "UTF-8");
            addProperty(path, content);

            //获取指定节点的子节点的数据
            List<String> children = zk.getChildren(path, false);
            for (String node : children)
                addZKNodeProperties(path + "/" + node);

        } catch (UnsupportedEncodingException e) {
            logger.info("{}:不支持zookeeper上的配置内容的字符", path);
        } catch (KeeperException e) {
            logger.info("{}:获取zookeeper配置时不存在:{}", path, path);
        } catch (InterruptedException e) {
            logger.error(e.toString(), e);
        }

    }

    /**
     * 链接zk， 阻塞的，不成功返回null
     */
    private void connectToZK() {
        if (zookeeperUrl == null || zookeeperUrl.length() == 0)
            return;
        //准备链接zk
        CountDownLatch connectedLatch = new CountDownLatch(1);
        Watcher watcher = new ConnectedWatcher(connectedLatch);
        try {
            zk = new ZooKeeper(zookeeperUrl, 3000, watcher);
            waitUntilConnected(zk, connectedLatch);
            logger.info("与ZooKeeper服务器{} 连接成功 ", zookeeperUrl);
        } catch (IOException e) {
            logger.info("与ZooKeeper服务器 {} 连接失败 ", zookeeperUrl);
        }
    }

    private void addProperty(String nodeName, String value) {
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

    /**
     * 写入到global.properties文件里
     */
    private void overWriterGlobalProperties() {
        BufferedWriter bw = null;
        try {
            //加载global.properties
            File file = getResourceAsFile(PROPERTIES_FILE);
            logger.info("重写的文件：{}", file);
            if (!file.exists())
                logger.info(file.createNewFile()?"创建global.properties":"修改global.properties");

            //一些额外的属性
            appendSomeProcessProperties();

            //保存
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
            properties.store(bw, null);
        } catch (IOException e) {
            logger.error("覆盖global.prorperties出错", e);
        } finally {
            IOUtils.closeStream(bw);
        }
    }

    /**
     * 合并本地的global.properties并保存，并增加一些额外的参数
     */
    private void megerLocalProperties() {

        BufferedReader br = null;
        OutputStreamWriter osw = null;
        try {
            //加载global.properties
            File file = getResourceAsFile(PROPERTIES_FILE);
            logger.info("合并本地的文件：{}", file);

            if (!file.exists())
                logger.info(file.createNewFile()?"创建global.properties":"修改global.properties");
            else {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                properties.load(br);
            }

            appendSomeProcessProperties();

            //保存
            osw = new OutputStreamWriter(new FileOutputStream(file));
            properties.store(osw, "合并后的properties");
        } catch (IOException e) {
            logger.error("合并global.prorperties出错", e);
            IOUtils.closeStream(osw);
            IOUtils.closeStream(br);
        }
    }

    private File getResourceAsFile(String resource) {
        return new File(Thread.currentThread().getContextClassLoader().getResource("").getPath() + resource);
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

        if ("product".equals(devProduct)) {
            properties.setProperty(subscribe, "false");
            properties.setProperty(register, "false");
        } else {
            if (devSubscribe == null || devSubscribe.length() == 0)
                properties.setProperty(subscribe, "true");
            if (devRegister == null || devRegister.length() == 0)
                properties.setProperty(register, "true");
        }

        //去掉前后空格和/t
        String value;
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            value = (String) entry.getValue();
            value = value.replace("\t+", "").trim();
            properties.put(entry.getKey(), value);
        }

        //设置spring的profile
        String springProfile = properties.getProperty("spring.profiles.active");
        if (springProfile != null && springProfile.trim().length() > 0)
            System.setProperty("spring.profiles.active", springProfile.trim());

    }

    /**
     * 获取参数值，查找顺序：环境变量，虚拟机变量，默认值
     */
    public String getConfigParameter(String key, String defaultValue) {
        String res;
        res = System.getenv(key);
        if (res == null || res.length() == 0) {
            res = System.getProperty(key);
            if (res == null || res.length() == 0)
                res = defaultValue;
        }
        return res;
    }

    /**
     * 增加公共配置的路径，默认不配置使用"common-conf",有配置可用common-conf=xxx,xxx配置
     */
    public void initCommonConfPaths() {
        if (!CollectionUtils.isEmpty(commonConfPathList))
            return;

        String commonConf = getConfigParameter(COMMON_CONF, COMMON_CONF_DEFAULT);
        commonConfPathList = Arrays.asList(commonConf.split(","));
    }

    private static void waitUntilConnected(ZooKeeper zooKeeper, CountDownLatch connectedLatch) {
        if (States.CONNECTING == zooKeeper.getState()) {
            try {
                connectedLatch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static void main(String[] args) {
        ZookeeperPropertyListener zklistener = new ZookeeperPropertyListener();
        /*兼容以前的
         * zklistener.setEnvironmentName("zookeeper");
    	zklistener.setApplicationName("quota");*/

    	/*兼容以前的*/
        /*zklistener.setApplicationName("sq-uam2");*/

        /*	zklistener.setApplicationName("sq_uam_portal");
        zklistener.setCommonConfPathList(Arrays.asList("common-db","common-other"));*/

    	/*zklistener.setApplicationName("sq-uam");
        zklistener.setCommonConfPathList(Arrays.asList("common-db","common-other"));*/

    	/*中文乱码*/
        zklistener.setApplicationName("sq-uam2");

        zklistener.setEnvironmentName("dev");
        zklistener.setZookeeperUrl("192.168.52.125:2181");
        zklistener.contextInitialized(null);
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
