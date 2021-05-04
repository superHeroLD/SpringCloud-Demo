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

备注：看源码的时候我还发现了集中别的获取服务器地址的方式，比如`ConfigurationBasedServerList`可以通过配置获取服务器地址信息，用逗号分隔开。其他的还有 `DomainExtractingServerList`、`StaticServerList`（直接通过代码构造）