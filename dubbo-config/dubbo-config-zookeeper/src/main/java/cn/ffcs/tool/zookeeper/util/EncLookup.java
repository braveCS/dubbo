package cn.ffcs.tool.zookeeper.util;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.lookup.StrLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Plugin(name = "enc", category = StrLookup.CATEGORY)
public class EncLookup  implements StrLookup {

    private static final Logger LOGGER= LoggerFactory.getLogger(EncLookup.class);

    @Override
    public String lookup(String key) {
        try {
            return ConfigTools.decrypt(key);
        } catch (Exception e) {
            LOGGER.error("解析参数失败："+key,e);
            return key;
        }
    }

    @Override
    public String lookup(LogEvent event, String key) {
        return lookup(key);
    }
}
