# Ribbon学习

## Ribbon项目结构

ribbon-archaius

ribbon-core

ribbon-erueka:ribbon与eureka整合，主要是提供了发现服务地址相关的方法。

ribbon-evcache：

ribbon-guice

ribbon-httpclient

ribbon-loadbalanceer

robbon-test

ribbon-transport



单独看ribbon的源码没什么用，还是要结合Spring cloud相关的源码开始看。Spring Cloud为了整合Nitflix相关的技术写了很多整合的代码。

## Ribbon在SpringCloud中的工作原理

### 负载均衡器自动装配（没有引入Ribbon的流程）

```java
@Bean
@LoadBalanced
RestTemplate restTemplate() {
    return new RestTemplate();
}
```

Ribbon在SpringCloud中使用的时候，会用`@LoadBalanced`注解修饰`RestTemplate`。被`@LoadBalanced`注解修饰后，SpringCloud在服务启动时，会通过`org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration`进行自动装配。主要执行了如下方法

```java
@Bean
public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(
      final ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {
   return () -> restTemplateCustomizers.ifAvailable(customizers -> {
      for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
         for (RestTemplateCustomizer customizer : customizers) {
            customizer.customize(restTemplate);
         }
      }
   });
}
```

通过`loadBalancedRestTemplateInitializerDeprecated()`方法对Spring容器中的RestTemplate进行定制。

```java
@Configuration(proxyBeanMethods = false)
@Conditional(RetryMissingOrDisabledCondition.class)
static class LoadBalancerInterceptorConfig {

   @Bean
   public LoadBalancerInterceptor loadBalancerInterceptor(LoadBalancerClient loadBalancerClient,
         LoadBalancerRequestFactory requestFactory) {
      return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
   }

   @Bean
   @ConditionalOnMissingBean
   public RestTemplateCustomizer restTemplateCustomizer(final LoadBalancerInterceptor loadBalancerInterceptor) {
      return restTemplate -> {
         List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
         list.add(loadBalancerInterceptor);
         restTemplate.setInterceptors(list);
      };
   }

}
```

上面的方法就是具体的定制过程，其实就是给每个RestTemplate中都加入了`LoadBalancerInterceptor`拦截器。`LoadBalancerInterceptor`拦截器会拦截每个RestTemplate中的请求，然后进行负载均衡操作。

通过自动装配的过程可以看出Ribbon其实就是通过拦截器拦截每个RestTemplate的请求进行负载均衡的。

### LoadBalancerInterceptor原理

在`LoadBalancerInterceptor#intercept()`方法中会拦截每个Http请求。

```java
@Override
public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
      final ClientHttpRequestExecution execution) throws IOException {
   final URI originalUri = request.getURI();
   String serviceName = originalUri.getHost();
   Assert.state(serviceName != null, "Request URI does not contain a valid hostname: " + originalUri);
   return this.loadBalancer.execute(serviceName, this.requestFactory.createRequest(request, body, execution));
}
```

- 首先，获得请求的URL，并且从URL中获取服务名称serviceName，比如ServerA，后面会用这个服务名称去获取服务地址。
- 然后封装请求，将入参封装为LoadBalancerRequest。

this.loadBalancer.execute这一行代码最终会调用到`org.springframework.cloud.loadbalancer.blocking.client.BlockingLoadBalancerClient#execute()`方法中，

```java
@Override
public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
   String hint = getHint(serviceId);
   LoadBalancerRequestAdapter<T, DefaultRequestContext> lbRequest = new LoadBalancerRequestAdapter<>(request,
         new DefaultRequestContext(request, hint));
   Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
         .getSupportedLifecycleProcessors(
               loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
               DefaultRequestContext.class, Object.class, ServiceInstance.class);
   supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
  //这里应该是进行了复杂均衡算法
   ServiceInstance serviceInstance = choose(serviceId, lbRequest);
   if (serviceInstance == null) {
      supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
            new CompletionContext<>(CompletionContext.Status.DISCARD, lbRequest, new EmptyResponse())));
      throw new IllegalStateException("No instances available for " + serviceId);
   }
  //这里应该执行了具体的Http请求
   return execute(serviceId, serviceInstance, lbRequest);
}
```

在这里 choose(serviceId, lbRequest)应该是进行了服务实例的选取过程。choose代码如下。

```java
@Override
public <T> ServiceInstance choose(String serviceId, Request<T> request) {
   ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerClientFactory.getInstance(serviceId);
   if (loadBalancer == null) {
      return null;
   }
   Response<ServiceInstance> loadBalancerResponse = Mono.from(loadBalancer.choose(request)).block();
   if (loadBalancerResponse == null) {
      return null;
   }
   return loadBalancerResponse.getServer();
}
```

这里会调用`LoadBalancerClientFactory#getInstance()`方法，在getInstance()方法中主要是获取了独立的AnnotationConfigApplicationContext上下文，然后从上下文中根据服务名称获取对应的服务实例`ServiceInstance`，看看这个`ServiceInstance`是不是跟Eureka中的很像，这里面就包括了服务实例的信息比如IP，端口等等。

```java
public ReactiveLoadBalancer<ServiceInstance> getInstance(String serviceId) {
   return getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class);
}
```

在`getInstance()`方法中实际上就根据Ribbon提供的LoadBalancer负载均衡算法选取了服务实例。这里我找到了两个负载均衡算法。

RandomLoadBalancer：随机选取服务实例提供服务

RoundRobinLoadBalancer：轮训请求服务实例（记录了上一次请求的服务实例，然后根据上一次请求的服务实例计算获取这次要请求的服务实例）

在获取服务实例完成后，在根据服务实例信息进行http请求。

```java
try {
   T response = request.apply(serviceInstance);
   Object clientResponse = getClientResponse(response);
   supportedLifecycleProcessors
         .forEach(lifecycle -> lifecycle.onComplete(new CompletionContext<>(CompletionContext.Status.SUCCESS,
               lbRequest, defaultResponse, clientResponse)));
   return response;
}
```

具体的请求是通过`LoadBalancerRequestAdapter`发出的，随后返回了请求信息。

> 这里我有个猜测，我的项目中引入的是spring-cloud-starter-netflix-eureka-client这个包，这里没有用Ribbon，所以可以看出Eureka-Client本身也是提供负载均衡服务的，只不过提供的比较少，就两种方式。第二点，SpringCloud还是有点厉害的，自动装配会根据一些配置条件自动的进行服务配置。比如没有Ribbon的时候，就用Eureka-Client自带的负载均衡，有了之后会不会直接用Ribbon的？需要根据配置哦

### 引入了Ribbon的自动装配流程

```java
@Configuration
@Conditional(RibbonAutoConfiguration.RibbonClassesConditions.class)
@RibbonClients
@AutoConfigureAfter(
      name = "org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration")
@AutoConfigureBefore({ LoadBalancerAutoConfiguration.class,
      AsyncLoadBalancerAutoConfiguration.class })
@EnableConfigurationProperties({ RibbonEagerLoadProperties.class,
      ServerIntrospectorProperties.class })
//一般都是通过这个配置开启Ribbon负载的
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.ribbon.enabled",
      havingValue = "true", matchIfMissing = true)
```

在项目中引入了Ribbon的时候，Spring在启动时就会根据条件自动执行Ribbon的自动装配类`RibbonAutoConfiguration`。前面的过程基本上与上面写的都相同，只不过在自动装配`LoadBalancerAutoConfiguration`中时，向其中注入的LoadBalancerClient类是`org.springframework.cloud.netflix.ribbon.RibbonLoadBalancerClient`,随后执行`RibbonLoadBalancerClient#execute()`方法。

```java
public <T> T execute(String serviceId, LoadBalancerRequest<T> request, Object hint)
      throws IOException {
  //获取负载均衡算法
   ILoadBalancer loadBalancer = getLoadBalancer(serviceId);
   Server server = getServer(loadBalancer, hint);
   if (server == null) {
      throw new IllegalStateException("No instances available for " + serviceId);
   }
   RibbonServer ribbonServer = new RibbonServer(serviceId, server,
         isSecure(server, serviceId),
         serverIntrospector(serviceId).getMetadata(server));

   return execute(serviceId, ribbonServer, request);
}
```

方法执行逻辑如下：

- 获取负载均衡算法（默认是ZoneAwareLoadBalancer）
- 根据负载均衡算法选取需要执行的Server
- 封装参数RibbonServer
- 执行实际的Http调用

#### getLoadBalancer选择负载均衡算法流程

```java
protected ILoadBalancer getLoadBalancer(String serviceId) {
   return this.clientFactory.getLoadBalancer(serviceId);
}
```

具体的逻辑就是从SpringClientFactory中根据配置获取`ILoadBalancer`，Ribbon中默认获取的是ZoneAwareLoadBalancer。在看ZoneAwareLoadBalancer的源码时发现`setServerListForZones()`方法，这个放过就应该是初始化了服务列表，重点看下这里的逻辑。

```java
@Override
protected void setServerListForZones(Map<String, List<Server>> zoneServersMap) {
    super.setServerListForZones(zoneServersMap);
    if (balancers == null) {
        balancers = new ConcurrentHashMap<String, BaseLoadBalancer>();
    }
    for (Map.Entry<String, List<Server>> entry: zoneServersMap.entrySet()) {
       String zone = entry.getKey().toLowerCase();
        getLoadBalancer(zone).setServersList(entry.getValue());
    }
    // check if there is any zone that no longer has a server
    // and set the list to empty so that the zone related metrics does not
    // contain stale data
    for (Map.Entry<String, BaseLoadBalancer> existingLBEntry: balancers.entrySet()) {
        if (!zoneServersMap.keySet().contains(existingLBEntry.getKey())) {
            existingLBEntry.getValue().setServersList(Collections.emptyList());
        }
    }
}  
```

这里有一行代码super.setServerListForZones(zoneServersMap)直观上就感觉这里获取了所有的服务列表，跟着跳转到父类`DynamicServerListLoadBalancer`方法setServerListForZones()

```java
protected void setServerListForZones(
        Map<String, List<Server>> zoneServersMap) {
    LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
    getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
}

 public void updateZoneServerMapping(Map<String, List<Server>> map) {
        upServerListZoneMap = new ConcurrentHashMap<String, List<? extends Server>>(map);
        // make sure ZoneStats object exist for available zones for monitoring purpose
        for (String zone: map.keySet()) {
            getZoneStats(zone);
        }
 }
```

发现所有的服务列表应该在此之前就已经初始化了，但是找了半天通过Debug发现在`DynamicServerListLoadBalancer`初始化的时候构造方法中通过 restOfInit(clientConfig)方法加载的所有服务端列表。

```java
public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                     ServerList<T> serverList, ServerListFilter<T> filter,
                                     ServerListUpdater serverListUpdater) {
    super(clientConfig, rule, ping);
    this.serverListImpl = serverList;
    this.filter = filter;
    this.serverListUpdater = serverListUpdater;
    if (filter instanceof AbstractServerListFilter) {
        ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
    }
    restOfInit(clientConfig);
}
```

```java
void restOfInit(IClientConfig clientConfig) {
    boolean primeConnection = this.isEnablePrimingConnections();
    // turn this off to avoid duplicated asynchronous priming done in BaseLoadBalancer.setServerList()
    this.setEnablePrimingConnections(false);
  
  //从Eureka中同步注册信息任务，
    enableAndInitLearnNewServersFeature();
  
  //从Eureka中获取服务注册信息
    updateListOfServers();
    if (primeConnection && this.getPrimeConnections() != null) {
        this.getPrimeConnections()
                .primeConnections(getReachableServers());
    }
    this.setEnablePrimingConnections(primeConnection);
    LOGGER.info("DynamicServerListLoadBalancer for client {} initialized: {}", clientConfig.getClientName(), this.toString());
}
```

跟着这个方法一步步走下去`updateListOfServers()`,然后到`DiscoveryEnabledNIWSServerList#getUpdatedListOfServers()`方法中。

```java
private List<DiscoveryEnabledServer> obtainServersViaDiscovery() {
    List<DiscoveryEnabledServer> serverList = new ArrayList<DiscoveryEnabledServer>();

    if (eurekaClientProvider == null || eurekaClientProvider.get() == null) {
        logger.warn("EurekaClient has not been initialized yet, returning an empty list");
        return new ArrayList<DiscoveryEnabledServer>();
    }

    EurekaClient eurekaClient = eurekaClientProvider.get();
    if (vipAddresses!=null){
        for (String vipAddress : vipAddresses.split(",")) {
            // if targetRegion is null, it will be interpreted as the same region of client
            List<InstanceInfo> listOfInstanceInfo = eurekaClient.getInstancesByVipAddress(vipAddress, isSecure, targetRegion);
            for (InstanceInfo ii : listOfInstanceInfo) {
                if (ii.getStatus().equals(InstanceStatus.UP)) {

                    if(shouldUseOverridePort){
                        if(logger.isDebugEnabled()){
                            logger.debug("Overriding port on client name: " + clientName + " to " + overridePort);
                        }

                        // copy is necessary since the InstanceInfo builder just uses the original reference,
                        // and we don't want to corrupt the global eureka copy of the object which may be
                        // used by other clients in our system
                        InstanceInfo copy = new InstanceInfo(ii);

                        if(isSecure){
                            ii = new InstanceInfo.Builder(copy).setSecurePort(overridePort).build();
                        }else{
                            ii = new InstanceInfo.Builder(copy).setPort(overridePort).build();
                        }
                    }

                    DiscoveryEnabledServer des = createServer(ii, isSecure, shouldUseIpAddr);
                    serverList.add(des);
                }
            }
            if (serverList.size()>0 && prioritizeVipAddressBasedServers){
                break; // if the current vipAddress has servers, we dont use subsequent vipAddress based servers
            }
        }
    }
    return serverList;
}
```

可以看出最终Ribbon也是通过Eureka-Client从Eureak中获取了所有的服务信息。

> 备注：看源码的时候我还发现了集中别的获取服务器地址的方式，比如`ConfigurationBasedServerList`可以通过配置获取服务器地址信息，用逗号分隔开。其他的还有 `DomainExtractingServerList`、`StaticServerList`（直接通过代码构造）

总结一下：Ribbon中，在加载ILoadBalancer时，就会通过Eureka初始化好对应的服务器列表。不过还有几个问题没弄太明白，比如SpringClientFacotry中的每个服务对应的ApplicationContext是怎么回事？

#### Ribbon持续获取Eureka的服务信息

Eureka中的服务注册信息也会不断的改变，所以Ribbon也会不断的进行同步到自己本地。同步的位置就在于`DynamicServerListLoadBalancer#restOfInit()#enableAndInitLearnNewServersFeature()`方法中，

```java
public void enableAndInitLearnNewServersFeature() {
    LOGGER.info("Using serverListUpdater {}", serverListUpdater.getClass().getSimpleName());
    serverListUpdater.start(updateAction);
}
```

其中serverListUpdater是`PollingServerListUpdater`在这里，创建了一个线程，每隔一定的时间（30秒）就会执行updateAction的逻辑，其中updateAction如下

```java
protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
    @Override
    public void doUpdate() {
        updateListOfServers();
    }
};
```

就是调用了updateListOfServers()放大去Eureka中拉取服务注册表。

#### Ribbon选择一个Server出来

Server server = getServer(loadBalancer, hint)这行代码就是通过负载均衡算法选择一个Server出来，以`ZoneAwareLoadBalancer`算法为例

```java
public Server chooseServer(Object key) {
    if (!ENABLED.get() || getLoadBalancerStats().getAvailableZones().size() <= 1) {
        logger.debug("Zone aware logic disabled or there is only one zone");
        return super.chooseServer(key);
    }
    Server server = null;
    try {
        LoadBalancerStats lbStats = getLoadBalancerStats();
        Map<String, ZoneSnapshot> zoneSnapshot = ZoneAvoidanceRule.createSnapshot(lbStats);
        logger.debug("Zone snapshots: {}", zoneSnapshot);
        if (triggeringLoad == null) {
            triggeringLoad = DynamicPropertyFactory.getInstance().getDoubleProperty(
                    "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".triggeringLoadPerServerThreshold", 0.2d);
        }

        if (triggeringBlackoutPercentage == null) {
            triggeringBlackoutPercentage = DynamicPropertyFactory.getInstance().getDoubleProperty(
                    "ZoneAwareNIWSDiscoveryLoadBalancer." + this.getName() + ".avoidZoneWithBlackoutPercetage", 0.99999d);
        }
        Set<String> availableZones = ZoneAvoidanceRule.getAvailableZones(zoneSnapshot, triggeringLoad.get(), triggeringBlackoutPercentage.get());
        logger.debug("Available zones: {}", availableZones);
        if (availableZones != null &&  availableZones.size() < zoneSnapshot.keySet().size()) {
            String zone = ZoneAvoidanceRule.randomChooseZone(zoneSnapshot, availableZones);
            logger.debug("Zone chosen: {}", zone);
            if (zone != null) {
                BaseLoadBalancer zoneLoadBalancer = getLoadBalancer(zone);
                server = zoneLoadBalancer.chooseServer(key);
            }
        }
    } catch (Exception e) {
        logger.error("Error choosing server using zone aware logic for load balancer={}", name, e);
    }
    if (server != null) {
        return server;
    } else {
        logger.debug("Zone avoidance logic is not invoked.");
        return super.chooseServer(key);
    }
}
```

核心选取算法会调用super.chooseServer(key)方法，这个方法会最终调用到父类BaseLoadBalancer中的chooseServer方法，最终通过rule.choose(key)方法选取服务器。

```java
public Server chooseServer(Object key) {
    if (counter == null) {
        counter = createCounter();
    }
    counter.increment();
    if (rule == null) {
        return null;
    } else {
        try {
            return rule.choose(key);
        } catch (Exception e) {
            logger.warn("LoadBalancer [{}]:  Error choosing server for key {}", name, key, e);
            return null;
        }
    }
}
```

```java
protected IRule rule = DEFAULT_RULE;
private final static IRule DEFAULT_RULE = new RoundRobinRule();
```

最终看到IRule就是RoundRobinRule()，应该就是轮训算法。

#### 重写URL发送HTTP请求

流程继续往下走，目前已经选定了要发送的Server信息，接下来就是要根据Server实际的Url和端口替换掉原来url中的信息。在`RibbonLoadBalancerClient#execute()`方法中执行具体的Http请求。

```java
public <T> T execute(String serviceId, ServiceInstance serviceInstance,
      LoadBalancerRequest<T> request) throws IOException {
   Server server = null;
   if (serviceInstance instanceof RibbonServer) {
      server = ((RibbonServer) serviceInstance).getServer();
   }
   if (server == null) {
      throw new IllegalStateException("No instances available for " + serviceId);
   }

   RibbonLoadBalancerContext context = this.clientFactory
         .getLoadBalancerContext(serviceId);
   RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);

   try {
      T returnVal = request.apply(serviceInstance);
      statsRecorder.recordStats(returnVal);
      return returnVal;
   }
  //省略掉一些异常代码
}
```

这里通过request.apply(serviceInstance)调用发送Http请求

```java
@Override
public ListenableFuture<ClientHttpResponse> intercept(final HttpRequest request,
      final byte[] body, final AsyncClientHttpRequestExecution execution)
      throws IOException {
   final URI originalUri = request.getURI();
   String serviceName = originalUri.getHost();
   return this.loadBalancer.execute(serviceName,
         new LoadBalancerRequest<ListenableFuture<ClientHttpResponse>>() {
            @Override
            public ListenableFuture<ClientHttpResponse> apply(
                  final ServiceInstance instance) throws Exception {
               HttpRequest serviceRequest = new ServiceRequestWrapper(request,
                     instance, AsyncLoadBalancerInterceptor.this.loadBalancer);
               return execution.executeAsync(serviceRequest, body);
            }

         });
}
```

最终通过`ServiceRequestWrapper`把请求出一个新的HttpRequest，在ServiceRequestWrapper中将根据服务实例信息和请求信息修改URL比如http://ServerA/XXX变为http://localhost:8080/XXX

```java
@Override
public URI getURI() {
   URI uri = this.loadBalancer.reconstructURI(this.instance, getRequest().getURI());
   return uri;
}
```

最终通过`AsyncClientHttpRequestExecution`将请求发出去。

到这里Ribbon负载均衡的大体流程就梳理完毕了，简单总结一下在没用Feign的情况下，Ribbon + Eureka处理负载均衡请求的流程如下

- Spring中Eureka Client或者Ribbon的自动装配会为RestTemplate里增加一个拦截器。这个拦截器会拦截每个Http请求进行负载均衡处理。拦截器会自动注入LoadBalanceClient，这个由Eureka Client或Ribbon具体实现。
- Ribbon的负载均衡Client主要负责加载对应的负载均衡算法并执行Http请求。这里有一些细节
  1. Ribbon的负载均衡算法会在初始化的时候就从Eureka中获取所有服务列表
  2. SpringCloud中每个服务都会有一个对应的ApplicationContext（但是具体有什么用）目前只知道可以从这里取出对应的负载均衡算法
  3. Ribbon负载均衡算法会在启动后启动一个`PollingServerListUpdater`定时任务，默认每30S就从Eureka中同步一下服务注册信息
  4. 通过负载均衡算法Ribbon会从中选取一台机器用于Http请求。
  5. 通过选取的服务信息和请求的URL，Ribbon会重新组装一个新的HttpRequest（ServiceRequestWrapper）修改URL，同时用修改后的Url发起Http（看代码这里的请求还是一个异步的请求）

#### Ribbon ping机制

`BaseLoadBalancer`在构造方法中会执行`initWithConfig`方法

```java
void initWithConfig(IClientConfig clientConfig, IRule rule, IPing ping, LoadBalancerStats stats) {
    this.config = clientConfig;
    String clientName = clientConfig.getClientName();
    this.name = clientName;
    int pingIntervalTime = Integer.parseInt(""
            + clientConfig.getProperty(
                    CommonClientConfigKey.NFLoadBalancerPingInterval,
                    Integer.parseInt("30")));
    int maxTotalPingTime = Integer.parseInt(""
            + clientConfig.getProperty(
                    CommonClientConfigKey.NFLoadBalancerMaxTotalPingTime,
                    Integer.parseInt("2")));

    setPingInterval(pingIntervalTime);
    setMaxTotalPingTime(maxTotalPingTime);

    // cross associate with each other
    // i.e. Rule,Ping meet your container LB
    // LB, these are your Ping and Rule guys ...
    setRule(rule);
    setPing(ping);

    setLoadBalancerStats(stats);
    rule.setLoadBalancer(this);
    if (ping instanceof AbstractLoadBalancerPing) {
        ((AbstractLoadBalancerPing) ping).setLoadBalancer(this);
    }
    logger.info("Client: {} instantiated a LoadBalancer: {}", name, this);
    boolean enablePrimeConnections = clientConfig.get(
            CommonClientConfigKey.EnablePrimeConnections, DefaultClientConfigImpl.DEFAULT_ENABLE_PRIME_CONNECTIONS);

    if (enablePrimeConnections) {
        this.setEnablePrimingConnections(true);
        PrimeConnections primeConnections = new PrimeConnections(
                this.getName(), clientConfig);
        this.setPrimeConnections(primeConnections);
    }
    init();

}
```

在initWithConfig方法中会设置IPing，在setPing方法中会调用`setupPingTask`方法设置ping任务。

```java
void setupPingTask() {
    if (canSkipPing()) {
        return;
    }
    if (lbTimer != null) {
        lbTimer.cancel();
    }
    lbTimer = new ShutdownEnabledTimer("NFLoadBalancer-PingTimer-" + name,
            true);
    lbTimer.schedule(new PingTask(), 0, pingIntervalSeconds * 1000);
    forceQuickPing();
}
```

这里的逻辑就是每10S执行一次PingTask任务。

```java
class PingTask extends TimerTask {
    public void run() {
        try {
           new Pinger(pingStrategy).runPinger();
        } catch (Exception e) {
            logger.error("LoadBalancer [{}]: Error pinging", name, e);
        }
    }
}
```

```java
    public void runPinger() throws Exception {
        if (!pingInProgress.compareAndSet(false, true)) { 
            return; // Ping in progress - nothing to do
        }
        
        // we are "in" - we get to Ping

        Server[] allServers = null;
        boolean[] results = null;

        Lock allLock = null;
        Lock upLock = null;

        try {
            /*
             * The readLock should be free unless an addServer operation is
             * going on...
             */
            allLock = allServerLock.readLock();
            allLock.lock();
            allServers = allServerList.toArray(new Server[allServerList.size()]);
            allLock.unlock();

            int numCandidates = allServers.length;
          //这里就是调用了具体IPing接口的isAlive方法判断服务状态
            results = pingerStrategy.pingServers(ping, allServers);

            final List<Server> newUpList = new ArrayList<Server>();
            final List<Server> changedServers = new ArrayList<Server>();

            for (int i = 0; i < numCandidates; i++) {
                boolean isAlive = results[i];
                Server svr = allServers[i];
                boolean oldIsAlive = svr.isAlive();

                svr.setAlive(isAlive);

                if (oldIsAlive != isAlive) {
                    changedServers.add(svr);
                    logger.debug("LoadBalancer [{}]:  Server [{}] status changed to {}", 
                      name, svr.getId(), (isAlive ? "ALIVE" : "DEAD"));
                }

                if (isAlive) {
                    newUpList.add(svr);
                }
            }
            upLock = upServerLock.writeLock();
            upLock.lock();
            upServerList = newUpList;
            upLock.unlock();

            notifyServerStatusChangeListener(changedServers);
        } finally {
            pingInProgress.set(false);
        }
    }
}
```

PingTask任务的具体逻辑就是依次对每个服务调用IPing接口中的isAlive方法然后更新本地服务列表的状态。

Ribbon的默认IPing实现是`DummyPing`，具体实现就是直接返回true，其实就是不做任何操作不剔除任何服务实例。

```java
public boolean isAlive(Server server) {
    return true;
}
```

在Ribbon启用了注册中心后，那么就会使用`NIWSDiscoveryPing`这个类。

```java
public boolean isAlive(Server server) {
    boolean isAlive = true;
    if (server!=null && server instanceof DiscoveryEnabledServer){
           DiscoveryEnabledServer dServer = (DiscoveryEnabledServer)server;                
           InstanceInfo instanceInfo = dServer.getInstanceInfo();
           if (instanceInfo!=null){                    
               InstanceStatus status = instanceInfo.getStatus();
               if (status!=null){
                   isAlive = status.equals(InstanceStatus.UP);
               }
           }
       }
    return isAlive;
}
```

这给类就是调用注册中心的接口，然后获取服务状态来实现ping的逻辑。

由此可知也可以自己实现IPing然后装配到Spring中，从而实现自己想要的服务探活逻辑。