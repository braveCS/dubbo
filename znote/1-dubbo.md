# 核心模块职责介绍

![image-20191201225333874](F:\WORK\work_open\dubbo-2.7.x\znote\img\模块依赖图.png)

## + dubbo-common
**公共通用逻辑模块，提供 通用与业务领域无关的工具类和模型**:
io处理、日志处理、配置处理、类处理、线程池扩展、二进制代码处理、class编译处理、json处理、数据存储接口，系统版本号等等通用的类和接口。

## + dubbo-remoting

**远程通讯模块。提供通用的客户端和服务端的通讯功能**。
该模块定义了远程传输器、终端（endpoint）、客户端、服务端、编码解码器、数据交换、缓冲区、通讯异常定义等等核心的接口及类构成。
他是对于远程网络通讯的抽象。提供了诸如netty、mina、grizzly、http、p2p和zookeeper的协议和技术框架的实现方式。

## + dubbo-rpc
**远程调用模块：抽象各种协议，以及动态代理，只包含一对一的调用**，不关心集群的管理。包括：
服务发布，服务调用代理，远程调用结果及异常，rpc调用网络协议，rpc调用监听器和过滤器等等。
该模块提供了默认的基于dubbo协议的实现模块，还提供了hessian、http、rest、rmi、thrift和webservice等协议的实现，还实现了injvm的本地调用实现。

## + dubbo-cluster

**集群模块：将多个服务提供方伪装为一个提供方，**包括：负载均衡, 集群容错，路由，分组聚合等。集群的地址列表可以是静态配置的，也可以是由注册中心下发。 

容错->目录->路由->配置->负载均衡->合并结果

![image-20191201225546721](F:\WORK\work_open\dubbo-2.7.x\znote\img\集群模块流程图.png)

## + dubbo-registry
**注册中心模块**：基于注册中心下发地址的集群方式，以及对各种注册中心的抽象







## + dubbo-config

**配置模块** ，是 Dubbo 对外的 API，用户通过 Config 使用Dubbo，隐藏 Dubbo 所有细节。

![image-20191215175553440](F:\WORK\work_open\dubbo-2.7.x\znote\img\配置类图.png)

**不同粒度配置的覆盖关系**

以 timeout 为例，下图显示了配置的查找顺序，其它 retries, loadbalance, actives 等类似：

- 方法级优先，接口级次之，全局配置再次之。
- 如果级别一样，则消费方优先，提供方次之。

其中，服务提供方配置，通过 URL 经由注册中心传递给消费方。

![image-20191215183115867](F:\WORK\work_open\dubbo-2.7.x\znote\img\覆盖关系.png)

目前Dubbo支持的所有配置都是`.properties`格式的，包括`-D`、`Externalized Configuration`等，`.properties`中的所有配置项遵循一种`path-based`的配置格式：

```properties
# 应用级别
dubbo.{config-type}[.{config-id}].{config-item}={config-item-value}
# 服务级别
dubbo.service.{interface-name}[.{method-name}].{config-item}={config-item-value}
dubbo.reference.{interface-name}[.{method-name}].{config-item}={config-item-value}
# 多配置项
dubbo.{config-type}s.{config-id}.{config-item}={config-item-value}
```

## - dubbo-container

**容器模块**：是一个 Standlone 的容器，以简单的 Main 加载 Spring 启动，因为服务通常不需要 Tomcat/JBoss 等 Web 容器的特性，没必要用 Web 容器去加载服务。

## ~ dubbo-monitor

**监控模块，统计服务调用次数，调用时间的，调用链跟踪的服务**。

## + dubbo-filter

**过滤器模块**：提供了**内置**的过滤器。

## + dubbo-plugin

**过滤器模块**：提供了**内置**的插件。



## ~ dubbo-serialization

## ~ dubbo-compatible

## ~ dubbo-configcenter

## ~ dubbo-metadata

## ~ 各种POM

1. dubbo-all：all in one， 不用一个一个添加dubbo-xxx-xxx依赖
2. dubbo-bom：dubbo的依赖：**统一**定义了 Dubbo 的版本号，用在一个一个添加dubbo-xx-xx依赖时用到
3. dubbo-dependencies-bom：Maven BOM(Bill Of Materials) ，**统一**定义了 Dubbo 依赖的三方库的版本号
4. dubbo-distribution：发行版，包含demo工程

### 打包

jenkinsfile里有写

0. ```xml
   <!-- maven-shade-plugin -->
   <createDependencyReducedPom>true</createDependencyReducedPom>
   ```

1. dubbo-dependencies-bom:  mvn clean install -Dmaven.test.skip
2. dubbo-parent:   mvn clean install -Dmaven.test.skip

过程：service -> Config -> Proxy -> Registry -> Cluster -> Monitor -> Protocol -> Exchange -> Transport -> Serialize 

### 动态配置中心

配置中心（v2.7.0）在Dubbo中承担两个职责：

1. 外部化配置。启动配置的集中式存储 （简单理解为dubbo.properties的外部化存储）。
2. 服务治理。服务治理规则的存储与通知。

选一个合适的 Apollo, Nacos

1. 优先级：**-Ddubbo.config-center.highest-priority**=false
2. 作用域：外部化配置有全局和应用两个级别，全局配置是所有应用共享的，应用级配置是由每个应用自己维护且只对自身可见的。
3. 加密：
4. 参数化：

```xml
<dubbo:config-center address="zookeeper://127.0.0.1:2181"/>
```

默认所有的配置都存储在`/dubbo/config`节点，具体节点结构图如下：：

![image-20191215181851550](F:\WORK\work_open\dubbo-2.7.x\znote\img\节点结构图.png)

- namespace，用于不同配置的环境隔离。
- config，Dubbo约定的固定节点，不可更改，所有配置和服务治理规则都存储在此节点下。
- dubbo/application，分别用来隔离全局配置、应用级别配置：dubbo是默认group值，application对应应用名
- dubbo.properties，此节点的node value存储具体配置内容



![image-20191215182601779](F:\WORK\work_open\dubbo-2.7.x\znote\img\服务治理.png)

- namespace，用于不同配置的环境隔离。
- config，Dubbo约定的固定节点，不可更改，所有配置和服务治理规则都存储在此节点下。
- dubbo，所有服务治理规则都是全局性的，dubbo为默认节点
- configurators/tag-router/condition-router，不同的服务治理规则类型，node value存储具体规则内容

不同的规则以不同的key后缀区分：

- configurators，[覆盖规则](http://dubbo.apache.org/zh-cn/docs/user/demos/config-rule.html)
- tag-router，[标签路由](http://dubbo.apache.org/zh-cn/docs/user/demos/routing-rule.html)
- condition-router，[条件路由](http://dubbo.apache.org/zh-cn/docs/user/demos/routing-rule.html)





## maven最佳实践

1. flatten-maven-plugin 

   ```xml
   			<plugin><!--和reversion一起用-->
                   <groupId>org.codehaus.mojo</groupId>
                   <artifactId>flatten-maven-plugin</artifactId>
                   <version>${maven_flatten_version}</version>
                   <configuration>
                       <updatePomFile>true</updatePomFile>
                       <flattenMode>resolveCiFriendliesOnly</flattenMode>
                   </configuration>
                   <executions>
                       <execution>
                           <id>flatten</id>
                           <phase>process-resources</phase>
                           <goals>
                               <goal>flatten</goal>
                           </goals>
                       </execution>
                       <execution>
                           <id>flatten.clean</id>
                           <phase>clean</phase>
                           <goals>
                               <goal>clean</goal>
                           </goals>
                       </execution>
                   </executions>
               </plugin>
   ```

3. mvn:deploy在整合或者发布环境下执行，将最终版本的包拷贝到远程的repository，使得其他的开发者或者工程可以共享。

   ```xml
   <properties>
       <skip_maven_deploy>true</skip_maven_deploy>
   </properties>
   ```


   表示maven部署的时候跳过该工程。


4. <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
   <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
   <maven.compiler.encoding>UTF-8</maven.compiler.encoding> 
5. Maven多模块结构下版本管理的正确姿势(CI Friendly Versions) - ${revision} Maven 3.5.0-beta-1
6. pom多继承问题：单继承：maven的继承跟java一样，单继承，也就是说子model中只能出现一个parent标签；parent模块中，dependencyManagement中预定义太多的依赖，造成pom文件过长，而且很乱；如何让这些依赖可以分类并清晰的管理？问题解决：import scope依赖，如何使用：

   1. maven2.9以上版本
   2. 将dependency分类，每一类建立单独的pom文件3、在需要使用到这些依赖的子model中，使用dependencyManagement管理依赖，并import scope依赖
   3. 注意：scope=import只能用在dependencyManagement里面,且仅用于type=pom的dependency


# 参考

1. [dubbo - 项目结构一览](https://blog.csdn.net/qq_26857649/article/details/82996456)
2. [dubbo-官网用户文档](http://dubbo.apache.org/zh-cn/docs/user/quick-start.html)
3. [dubbo-官网博客](http://dubbo.apache.org/zh-cn/blog/index.html)
4. [阿里技术专家详解Dubbo实践，演进及未来规划](https://mp.weixin.qq.com/s/trM9KBE79cKayzgorEkilQ)
5. 



# 后续

1. dubbo-initializer
2. itemp 里的dubbo收藏
3. 微信里的dubbo收藏
4. 

# git操作

1. fork 原始仓库
2. clone 自己的仓库
3. 在 master 分支添加原始仓库为远程分支 git remote add upstream 远程仓库
4. 自己分支开发，如 dev 分支开发：git checkout -b dev
5. 本地 dev 提交
6. 切换 master 分支，同步原始仓库：git checkout master， git pull upstream master
7. 切换本地 dev 分支，合并本地 master 分支（已经和原始仓库同步），可能需要解冲突
8.  git push origin test-pr
9. 提交本地 dev 分支到自己的远程 dev 仓库
10. 现在才是给原始仓库发 pull request 请求
11. 等待原作者回复（接受/拒绝）

ffcs   <--- master  <-- 2.6.x

git fetch upstream

git checkout master

git merge  upstream/2.6.x

git checkout ffcs

git merge master 

 



git status
git checkout master
git pull upstream master
git checkout ffcs
git merge master ffcs
git commint -a
git push origin ffcs



\#从源分支获取最新的代码 git fetch upstream;

git merge upstream/xxxx

\1. 先点击 fork 仓库，项目现在就在你的账号下了

![img](E:/note/sina1751790942/0ea5cb7430804cf6a20ce3eb072ea22f/b0a0ecb8_hd.jpeg)

![img](E:/note/sina1751790942/007b895476f24a95aef7e56e285fceba/c5affb0e_hd.jpeg)

\2. 在你自己的机器上 git clone 这个仓库，切换分支（也可以在 master 下），做一些修改。

~  git clone https://github.com/beepony/bootstrap.git ~  cd bootstrap ~  git checkout -b test-pr ~  git add . && git commit -m "test-pr" ~  git push origin test-pr

\3. 完成修改之后，回到 test-pr 分支，点击旁边绿色的 Compare & pull request 按钮

![img](E:/note/sina1751790942/8447c76a94394c81a2d1c53daa29d169/852e4199_hd.jpeg)

\4. 添加一些注释信息，确认提交

![img](E:/note/sina1751790942/5c440c1c634e4234b6b32a936f4bd3ca/c1933006_hd.jpeg)

\5. 仓库作者看到，你提的确实是对的，就会 merge，合并到他的项目中















































