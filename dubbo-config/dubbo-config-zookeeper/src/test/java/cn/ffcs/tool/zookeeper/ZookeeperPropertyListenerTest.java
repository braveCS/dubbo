package cn.ffcs.tool.zookeeper;

import org.apache.logging.log4j.core.lookup.Interpolator;
import org.apache.logging.log4j.core.lookup.MapLookup;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class ZookeeperPropertyListenerTest {


    @Test
    public void parsePropertiesTest() {

        Properties properties = new Properties();
        properties.put("db_ip_port_mysql", "127.0.0.1\\:3306");
        properties.put("jdbc.username", "root");
        properties.put("test.java", "${java:version}");
        properties.put("db_mysql_edi_user", "root");
        properties.put("db_mysql_root_pwd", "${enc:byEfKcGYZ7o5AJQnotkHAdS1j4o1X4IyQYSRlXhVBNAQfwfU5JtIRfXtQ5fjjGtIHigCvLrn0ZL7fRZJCq7UkA==}");
        properties.put("jdbc.url", "jdbc\\:mysql\\://${db_ip_port_mysql}/edi?allowMultiQueries\\=true&useSSL\\=true&useUnicode\\=true&characterEncoding\\=utf8&zeroDateTimeBehavior\\=convertToNull&autoReconnect\\=true");
        properties.put("test.env", "${env:JAVA_HOME:-adfasdf}");
        properties.put("test.date", "${date:yyyy-MM-dd}");
//        properties.put("test.sys", "${sys:db_mysql_roots:-${env:JAVA_HOME:-cdsacasd}}");
        properties.put("test.sys", "${sys:db_mysql_roots:-cdsacasd}");

        properties.put("test.jvmrunargs", "${jvmrunargs:adsf}");
        properties.put("test.base64", "${base64:YXNkYXNkZmFzZGY=}");

        /**占位符， 插值 ${env, system:} 类似log4j2 加密   ENC()*/
        Map<String, String> paramMap = new HashMap<>();
        Set<String> keys = properties.stringPropertyNames();

        //第一轮替换，基本替换
        StrSubstitutor log4jSubstitutor = new StrSubstitutor(new Interpolator(paramMap));
        substitutorProperties(properties,keys, paramMap, log4jSubstitutor);

        //第二轮替换，解密
        Interpolator encInterpolator = new Interpolator(new MapLookup(paramMap), Collections.singletonList("cn.ffcs.tool.zookeeper.util"));
        StrSubstitutor encSubstitutor = new StrSubstitutor(encInterpolator);
        substitutorProperties(properties,keys, paramMap, encSubstitutor);

        //第二轮替换，完整
        if(keys.size()>paramMap.size()) {
            substitutorProperties(properties, keys, paramMap, log4jSubstitutor);
        }

        keys.forEach(key->System.out.println(key+"="+properties.getProperty(key)));
    }

    private void substitutorProperties(Properties properties, Set<String> keys, Map<String, String> paramMap, StrSubstitutor substitutor) {
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


}
