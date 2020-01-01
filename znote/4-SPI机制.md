# SPI
SPI解决的是扩展内容配置和动态加载的问题。
在java中解决相同或者类似问题的技术有OSGI，JDK自带的SPI，以及IOC框架Spring也能够解决类似的问题，各种解决方案各有特点，我们不展开讲。
而dubbo的SPI是从JDK标准的SPI(Service Provider Interface)扩展点发现机制加强而来，它做了如下改进:
1. JDK标准的SPI会一次性实例化扩展点所有实现，如果有扩展实现初始化很耗时，但如果没用上也加载，会很浪费资源。
2. 如果扩展点加载失败，连扩展点的名称都拿不到了。比如：JDK标准的ScriptEngine，通过getName();获取脚本类型的名称，
但如果RubyScriptEngine因为所依赖的jruby.jar不存在，导致RubyScriptEngine类加载失败，这个失败原因被吃掉了，和ruby对应不起来，
当用户执行ruby脚本时，会报不支持ruby，而不是真正失败的原因。
3. 增加了对扩展点IoC和AOP的支持，一个扩展点可以直接setter注入其它扩展点。

SPI的源码位于工程dubbo-common的包com.alibaba.dubbo.common.extension下

## ExtensionLoader
该类是dubbo的SPI机制实现的最为核心的一个类，绝大多数实现逻辑均位于该类中,
通常获得一个扩展接口的实例使用如下接口方法获得:
ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); 



































