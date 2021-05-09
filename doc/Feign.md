# Feign学习

## Feign核心组件

**编码器和解码器**：Encoder和Decoder

**Logger**：Feign可以打印接口请求日志

**Contract**：比如一般来说Feign的@FeignClient注解和spring web mvc支持的@PathVariable、@RequestMapping、@RequestParams等注解结合起来使用，feign本来是没法支持spring web mvc的注解的，但是有一个Contract组件之后，契约组件，这个组件负责解释别人的注解，让feign可以跟别人的注解和起来使用。

**Feign.Builder**：Feign客户端的一个实例构造器。

**FeignClient**：feign最核心的入口，就是要构造一个FeignClient里面包含了一系列组件，比如说Encoder、Decoder、Logger、Contract等。

## SpringCloud对feign的默认组件

Decoder:ResponseEntityDecoder

Encoder:SpringEncoder

Logger:Slf4jLogger

Contract:SpringMvcContract

Feign实例构造器：HystrixFeign.Builder

Feign客户端：LoadBalancerFeignClient 跟Ribbon整合在一起使用的

**上面说的默认组件都可以通过自定义进行覆盖，从而实现自定义FeignClient，这些组件都是在哪里定义的呢，是在spring-cloud-openfeign-core这个工程中的`FeignClientsConfiguration`类里**

## Feign的配置

之前都没怎么研究过Feign配置，Feign配置主要分三块，普通参数配置、压缩配置、日志配置

这里后面在补充吧，这个看看别人的博客吧，应该有很多配置直接贴上来，捏哈哈哈

## Feign源码解析

### FeignClient自动装配

在使用`EnableFeignClients`注解启用Feign后，在`EnableFeignClients`注解中会引入`FeignClientsRegistrar`类,这个类就是负责将我们使用`@FeignClient`注解标注的接口注入到Spring中。

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(FeignClientsRegistrar.class)
public @interface EnableFeignClients
```

在`FeignClientsRegistrar`类中，Spring在启动时会执行`registerBeanDefinitions`方法，代码如下,已经用注释标注了具体时做什么的。

```java
@Override
public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
  //注册EnableFeignClients注解中的配置
   registerDefaultConfiguration(metadata, registry);
  //扫描@FeignClient注解的接口，装配到Spring中
   registerFeignClients(metadata, registry);
}
```

`registerDefaultConfiguration（）`方法比较简单，就不展开了。值得说明一下的是`defaultConfiguration`这个属性，这个属性是指FeignClient的默认配置配置了比入Encode、Decoder、Contract等。如果你想实现自己的配置，那么可以通过这个属性进行覆盖，覆盖的形式可以参考`FeignClientsConfiguration`也是加上注解`@Configuration`然后通过`@Bean`注解去覆盖指定的组件。

```java
public void registerFeignClients(AnnotationMetadata metadata,
      BeanDefinitionRegistry registry) {

   LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
  //这里还是获取了一下EnableFeignClients注解的配置，主要是为了用basePackages这个属性
   Map<String, Object> attrs = metadata
         .getAnnotationAttributes(EnableFeignClients.class.getName());
   final Class<?>[] clients = attrs == null ? null
         : (Class<?>[]) attrs.get("clients");
   if (clients == null || clients.length == 0) {
      ClassPathScanningCandidateComponentProvider scanner = getScanner();
      scanner.setResourceLoader(this.resourceLoader);
      scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
     //获取包的扫描路径，如果没有指定的话那么就会默认扫描SpringBoot启动类所在的路径，其实就
      Set<String> basePackages = getBasePackages(metadata);
      for (String basePackage : basePackages) {
        //扫描FeignClient注解注解的类，然后加入到列表中
         candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
      }
   }
   else {
      for (Class<?> clazz : clients) {
         candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz));
      }
   }
	
  //逐个处理扫描出来的类装配到Spring中
   for (BeanDefinition candidateComponent : candidateComponents) {
      if (candidateComponent instanceof AnnotatedBeanDefinition) {
         // verify annotated class is an interface
         AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
         AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
         Assert.isTrue(annotationMetadata.isInterface(),
               "@FeignClient can only be specified on an interface");

         Map<String, Object> attributes = annotationMetadata
               .getAnnotationAttributes(FeignClient.class.getCanonicalName());

         String name = getClientName(attributes);
       //装配配置
         registerClientConfiguration(registry, name,
               attributes.get("configuration"));
			//装配类
         registerFeignClient(registry, annotationMetadata, attributes);
      }
   }
}
```

上面的代码概括一下做了那些事情

- 读取`EnableFeignClients`注解中的配置，主要是为了`basePackages`这个属性，目的是设置接下来的扫描路径，如果用户没有配置扫描的包路径的话，那么就默认扫描SpringBoot启动类所在的包路径。其次如果用户指定了`clients`属性也就是指定了那些FeignClient实现类，那么就不尽兴扫描，直接将用户配置的类装配到Spring中。
- 将扫描出来的`@FeignClient`注解标注的类进行逐个处理，这里注意一下`@FeignClient`注解必须诸事在Interface上面，否则会抛出异常。
- 装配每个`@FeignClient`注解标注的类的配置到Spring中，这里所说的`configuration`也就是上面说的FeignClient的配置，比如Encode、Decode等。也就是说每个FeignClient都可以进行单独的配置上面说的那些属性。
- 装配`@FeignClient`注解标注的类到Spring中，通过`registerFeignClient`方法

```java
private void registerFeignClient(BeanDefinitionRegistry registry,
      AnnotationMetadata annotationMetadata, Map<String, Object> attributes) {
   String className = annotationMetadata.getClassName();
   Class clazz = ClassUtils.resolveClassName(className, null);
   ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory
         ? (ConfigurableBeanFactory) registry : null;
   String contextId = getContextId(beanFactory, attributes);
   String name = getName(attributes);
  //生成了FactoryBean
   FeignClientFactoryBean factoryBean = new FeignClientFactoryBean();
   factoryBean.setBeanFactory(beanFactory);
   factoryBean.setName(name);
   factoryBean.setContextId(contextId);
   factoryBean.setType(clazz);
   BeanDefinitionBuilder definition = BeanDefinitionBuilder
         .genericBeanDefinition(clazz, () -> {
            factoryBean.setUrl(getUrl(beanFactory, attributes));
            factoryBean.setPath(getPath(beanFactory, attributes));
            factoryBean.setDecode404(Boolean
                  .parseBoolean(String.valueOf(attributes.get("decode404"))));
            Object fallback = attributes.get("fallback");
            if (fallback != null) {
               factoryBean.setFallback(fallback instanceof Class
                     ? (Class<?>) fallback
                     : ClassUtils.resolveClassName(fallback.toString(), null));
            }
            Object fallbackFactory = attributes.get("fallbackFactory");
            if (fallbackFactory != null) {
               factoryBean.setFallbackFactory(fallbackFactory instanceof Class
                     ? (Class<?>) fallbackFactory
                     : ClassUtils.resolveClassName(fallbackFactory.toString(),
                           null));
            }
           //注意这里！！！直接就调用了FactoryBean.getObject方法
            return factoryBean.getObject();
         });
   definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
   definition.setLazyInit(true);
   validate(attributes);

   AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
   beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className);
   beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean);

   // has a default, won't be null
   boolean primary = (Boolean) attributes.get("primary");

   beanDefinition.setPrimary(primary);

   String[] qualifiers = getQualifiers(attributes);
   if (ObjectUtils.isEmpty(qualifiers)) {
      qualifiers = new String[] { contextId + "FeignClient" };
   }
	
  //生成了BeanDefinition
   BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className,
         qualifiers);
   BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
}
```

`registerFeignClient()`方法其实主要做了两件事

- 生成了`FeignClientFactoryBean`FeignClient的FactoryBean，然后通过对应的FactoryBean的`getObject()`方法生成对应的动态代理注入到Spring，在这一步会把所有FeignClient的功能与Ribbon之类的相关功能都整合初始化完毕。在实际使用的过程中，应该是使用的动态代理进行Http调用就行了。
- 生成了BeanDefinition注册到Spring中，通过`BeanDefinitionReaderUtils.registerBeanDefinition()`最终注册到`DefaultListableBeanFactory`中的`private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);`中。

### FeignClientFactoryBean方法getObject() 方法

```java
@Override
public Object getObject() {
   return getTarget();
}

/**
 * @param <T> the target type of the Feign client
 * @return a {@link Feign} client created with the specified data and the context
 * information
 */
<T> T getTarget() {
  //获取FeignContext
   FeignContext context = beanFactory != null
         ? beanFactory.getBean(FeignContext.class)
         : applicationContext.getBean(FeignContext.class);
  //构建Feign.Builder
   Feign.Builder builder = feign(context);

   if (!StringUtils.hasText(url)) {
      if (url != null && LOG.isWarnEnabled()) {
         LOG.warn(
               "The provided URL is empty. Will try picking an instance via load-balancing.");
      }
      else if (LOG.isDebugEnabled()) {
         LOG.debug("URL not provided. Will use LoadBalancer.");
      }
      if (!name.startsWith("http")) {
         url = "http://" + name;
      }
      else {
         url = name;
      }
      url += cleanPath();
      return (T) loadBalance(builder, context,
            new HardCodedTarget<>(type, name, url));
   }
   if (StringUtils.hasText(url) && !url.startsWith("http")) {
      url = "http://" + url;
   }
   String url = this.url + cleanPath();
   Client client = getOptional(context, Client.class);
   if (client != null) {
      if (client instanceof LoadBalancerFeignClient) {
         // not load balancing because we have a url,
         // but ribbon is on the classpath, so unwrap
         client = ((LoadBalancerFeignClient) client).getDelegate();
      }
      if (client instanceof FeignBlockingLoadBalancerClient) {
         // not load balancing because we have a url,
         // but Spring Cloud LoadBalancer is on the classpath, so unwrap
         client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
      }
      if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
         // not load balancing because we have a url,
         // but Spring Cloud LoadBalancer is on the classpath, so unwrap
         client = ((RetryableFeignBlockingLoadBalancerClient) client)
               .getDelegate();
      }
      builder.client(client);
   }
   Targeter targeter = get(context, Targeter.class);
   return (T) targeter.target(this, builder, context,
         new HardCodedTarget<>(type, name, url));
}
```

首先FeignContext，这里的FeginContext应该类似于SpringClientFacotry，每个FeignClient都对应了一个FeginContext，里面存了类似于`FeignClientsConfiguration`配置相关的信息。因为底层就是一个Map，Key对应的就是FeignClient的Name，Value是对应的配置。

然后`Feign.Builder builder = feign(context)`构建了一个`Feign.Builder`。

#### Feign.Builder构建过程

```java
protected Feign.Builder feign(FeignContext context) {
   FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
   Logger logger = loggerFactory.create(type);

   // @formatter:off
   Feign.Builder builder = get(context, Feign.Builder.class)
         // required values
         .logger(logger)
         .encoder(get(context, Encoder.class))
         .decoder(get(context, Decoder.class))
         .contract(get(context, Contract.class));
   // @formatter:on
   configureFeign(context, builder);
   applyBuildCustomizers(context, builder);

   return builder;
}
```

`Feign.Builder`顾名思义应该是构建FeignClient的构造器。从`FeignClientsConfiguration`中可以看出有两种Feign.Builder分别是

```java
//跟Hystrix相关
@Bean
@Scope("prototype")
@ConditionalOnMissingBean
@ConditionalOnProperty(name = "feign.hystrix.enabled")
public Feign.Builder feignHystrixBuilder() {
   return HystrixFeign.builder();
}
//跟重试相关,默认Builder，配置是从来不重试
	@Bean
	@Scope("prototype")
	@ConditionalOnMissingBean
	public Feign.Builder feignBuilder(Retryer retryer) {
		return Feign.builder().retryer(retryer);
	}
```

另外在`FeignClientsConfiguration`中可以看到很多FeignClient相关的默认配置，比如Encode、Decode等等。

从FeignContext中获取Encoder、Decoder、Contract之后，开始进入`configureFeign(context, builder)`方法根据配置信息配置Feign。

```java
protected void configureFeign(FeignContext context, Feign.Builder builder) {
  //这里是从yaml文件中读取以feign.client开头的配置 
  FeignClientProperties properties = beanFactory != null
         ? beanFactory.getBean(FeignClientProperties.class)
         : applicationContext.getBean(FeignClientProperties.class);
		// 这里从FeignContext中获取FeignClientConfigurer配置
   FeignClientConfigurer feignClientConfigurer = getOptional(context,
         FeignClientConfigurer.class);
   setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());
	 
  //下面开始用这些配置装配Feign.Builder
   if (properties != null && inheritParentContext) {
      if (properties.isDefaultToProperties()) {
         configureUsingConfiguration(context, builder);
         configureUsingProperties(
               properties.getConfig().get(properties.getDefaultConfig()),
               builder);
         configureUsingProperties(properties.getConfig().get(contextId), builder);
      }
      else {
         configureUsingProperties(
               properties.getConfig().get(properties.getDefaultConfig()),
               builder);
         configureUsingProperties(properties.getConfig().get(contextId), builder);
         configureUsingConfiguration(context, builder);
      }
   } else {
      configureUsingConfiguration(context, builder);
   }
}
```

上面加了一下注释，这里还是归纳一下

- 首先从yaml文件中读取以feign.client开头的配置
- 然后从FeignContext中获取FeignClientConfigurer的配置信息
- 然后根据这些配置装配Feign.Builder，比如重试次数，超时时间，连接超时时间等等。

上面装配完了之后就进入了`applyBuildCustomizers(context, builder)`方法，这个方法看名字就是实现Feign.Builder的一些定制化。

```java
private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
   Map<String, FeignBuilderCustomizer> customizerMap = context
         .getInstances(contextId, FeignBuilderCustomizer.class);

   if (customizerMap != null) {
      customizerMap.values().stream()
            .sorted(AnnotationAwareOrderComparator.INSTANCE)
            .forEach(feignBuilderCustomizer -> feignBuilderCustomizer
                  .customize(builder));
   }
   additionalCustomizers.forEach(customizer -> customizer.customize(builder));
}
```

这里应该是提供给开发人员可以扩展的接口。

全部完成后就完成了Feign.Builder的构建。

#### 生成FeignClient的动态代理

```java
if (!StringUtils.hasText(url)) {
   if (url != null && LOG.isWarnEnabled()) {
      LOG.warn(
            "The provided URL is empty. Will try picking an instance via load-balancing.");
   }
   else if (LOG.isDebugEnabled()) {
      LOG.debug("URL not provided. Will use LoadBalancer.");
   }
   if (!name.startsWith("http")) {
      url = "http://" + name;
   }
   else {
      url = name;
   }
   url += cleanPath();
   return (T) loadBalance(builder, context,
         new HardCodedTarget<>(type, name, url));
}
```

代码往下走，可以看到开始对url进行处理了，如果在`@FeignClient`中没有指定Url的话，那么这里的url应该为空，可以看到feign这里会对url进行处理，最终生成的url大概为http://service-provider ,这里就比较明显的能看出像是给Ribbon提供的url了，再结合下面的`return (T) loadBalance(builder, context,new HardCodedTarget<>(type, name, url))`应该会返回一个跟Ribbon相关的带有负载均衡的一个动态代理。看看这里的入参数：

- Feign.builder
- FeignContext
- type我这里是cn.ld.cloud.service.RandomService 也就是`@FeignClient`注解的类的类型
- Name 是`@FeignClient`注解中声明的名字，也就是你要调用的服务名称
- url就是上面生成的url 为http://service-provider

```java
protected <T> T loadBalance(Feign.Builder builder, FeignContext context,
      HardCodedTarget<T> target) {
   Client client = getOptional(context, Client.class);
   if (client != null) {
      builder.client(client);
      Targeter targeter = get(context, Targeter.class);
      return targeter.target(this, builder, context, target);
   }

   throw new IllegalStateException(
         "No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon or spring-cloud-starter-loadbalancer?");
}
```

这段代码我在调试的时候

- Client返回的是`LoadBalancerFeignClient`，这个又是啥呢？我找了一下源码发现这个类在`org.springframework.cloud.openfeign.ribbon`这个包下面，这个包下面应该就是Feign和Ribbon整合的相关代码了。这个包下面有这几个配置类

  1. DefaultFeignLoadBalancedConfiguration(默认的就是返回的LoadBalancerFeignClient)
  2. HttpClient5FeignLoadBalancedConfiguration（feign.httpclient.hc5.enabled = true）
  3. HttpClientFeignLoadBalancedConfiguration(feign.httpclient.enabled = true)
  4. OkHttpFeignLoadBalancedConfiguration(feign.okhttp.enabled = true)

  上面的几个配置应该是在yaml文件中配置后，就会用不同的技术生成对应的Http Client

- Targeter是`HystrixTargeter`

`HystrixTargeter`是个啥呢？看代码,`HystrixTargeter`是定义在了`FeignAutoConfiguration`中

```java
@Configuration(proxyBeanMethods = false)
@Conditional(FeignCircuitBreakerDisabledConditions.class)
@ConditionalOnClass(name = "feign.hystrix.HystrixFeign")
@ConditionalOnProperty(value = "feign.hystrix.enabled", havingValue = "true",
      matchIfMissing = true) //看这里，默认就是他，没有也是true
protected static class HystrixFeignTargeterConfiguration {
   @Bean
   @ConditionalOnMissingBean
   public Targeter feignTargeter() {
      return new HystrixTargeter();
   }
}
```

下面的代码是`HystrixTargeter#target`方法

```java
@Override
public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign,
      FeignContext context, Target.HardCodedTarget<T> target) {
   if (!(feign instanceof feign.hystrix.HystrixFeign.Builder)) {
     //如果没配置Hystrix的话，那么就会走这里
      return feign.target(target);
   }
   feign.hystrix.HystrixFeign.Builder builder = (feign.hystrix.HystrixFeign.Builder) feign;
   String name = StringUtils.isEmpty(factory.getContextId()) ? factory.getName()
         : factory.getContextId();
   SetterFactory setterFactory = getOptional(name, context, SetterFactory.class);
   if (setterFactory != null) {
      builder.setterFactory(setterFactory);
   }
  
  //Hystrix中的降级相关
   Class<?> fallback = factory.getFallback();
   if (fallback != void.class) {
      return targetWithFallback(name, context, target, builder, fallback);
   }
   Class<?> fallbackFactory = factory.getFallbackFactory();
   if (fallbackFactory != void.class) {
      return targetWithFallbackFactory(name, context, target, builder,
            fallbackFactory);
   }

   return feign.target(target);
}
```

看最后的`feign.target(target)`方法，实际上就是生成了动态代理

```java
public <T> T target(Target<T> target) {
  return build().newInstance(target);
}
```

```java
  public Feign build() {
    Client client = Capability.enrich(this.client, capabilities);
    Retryer retryer = Capability.enrich(this.retryer, capabilities);
    List<RequestInterceptor> requestInterceptors = this.requestInterceptors.stream()
        .map(ri -> Capability.enrich(ri, capabilities))
        .collect(Collectors.toList());
    Logger logger = Capability.enrich(this.logger, capabilities);
    Contract contract = Capability.enrich(this.contract, capabilities);
    Options options = Capability.enrich(this.options, capabilities);
    Encoder encoder = Capability.enrich(this.encoder, capabilities);
    Decoder decoder = Capability.enrich(this.decoder, capabilities);
    InvocationHandlerFactory invocationHandlerFactory =
        Capability.enrich(this.invocationHandlerFactory, capabilities);
    QueryMapEncoder queryMapEncoder = Capability.enrich(this.queryMapEncoder, capabilities);
		
    //这里是为了@FeignClient注解中的每个方法都生成了一个对应的处理方法
    SynchronousMethodHandler.Factory synchronousMethodHandlerFactory =
        new SynchronousMethodHandler.Factory(client, retryer, requestInterceptors, logger,
            logLevel, decode404, closeAfterDecode, propagationPolicy, forceDecoding);
    //这里是将方法名称和对应的处理方法相关联
    ParseHandlersByName handlersByName =
        new ParseHandlersByName(contract, options, encoder, decoder, queryMapEncoder,
            errorDecoder, synchronousMethodHandlerFactory);
    //生成动态代理
    return new ReflectiveFeign(handlersByName, invocationHandlerFactory, queryMapEncoder);
  }
}
```

到builder方法主要是为了生存动态代理做一些准备工作

- 根据配置生成针对每个方法的处理方法
- 将方法名称与处理方法相关联
- 调用`ReflectiveFeign`准备生成动态代理

接着调用`ReflectiveFeign#newInstance()`方法

```java
public <T> T newInstance(Target<T> target) {
  //针对每个方法生成对应的SynchronousMethodHandler
  Map<String, MethodHandler> nameToHandler = targetToHandlersByName.apply(target);
  Map<Method, MethodHandler> methodToHandler = new LinkedHashMap<Method, MethodHandler>();
  List<DefaultMethodHandler> defaultMethodHandlers = new LinkedList<DefaultMethodHandler>();

  for (Method method : target.type().getMethods()) {
    if (method.getDeclaringClass() == Object.class) {
      continue;
    } else if (Util.isDefault(method)) {
      DefaultMethodHandler handler = new DefaultMethodHandler(method);
      defaultMethodHandlers.add(handler);
      methodToHandler.put(method, handler);
    } else {
      //这里转换了一下对应关系把在上面生成的SynchronousMethodHandler和方法method对应起来
      methodToHandler.put(method, nameToHandler.get(Feign.configKey(target.type(), method)));
    }
  }
  //这里生成了动态代理的处理方法FeignInvocationHandler
  InvocationHandler handler = factory.create(target, methodToHandler);
  //这里生成了动态代理
  T proxy = (T) Proxy.newProxyInstance(target.type().getClassLoader(),
      new Class<?>[] {target.type()}, handler);

  for (DefaultMethodHandler defaultMethodHandler : defaultMethodHandlers) {
    defaultMethodHandler.bindTo(proxy);
  }
  //直接返回动态代理
  return proxy;
}
```

这里重点看一下`targetToHandlersByName.apply`方法的逻辑，apply方法生成了`@FeignClient`接口下每个方法对应的处理Handler

```java
  public Map<String, MethodHandler> apply(Target target) {
    //这里使用了SpringMVCContract处理了一下，解析出一下源数据信息，并且处理了一下SpringMVC中的RequestMapping标签
    List<MethodMetadata> metadata = contract.parseAndValidateMetadata(target.type());
    Map<String, MethodHandler> result = new LinkedHashMap<String, MethodHandler>();
    for (MethodMetadata md : metadata) {
      BuildTemplateByResolvingArgs buildTemplate;
      if (!md.formParams().isEmpty() && md.template().bodyTemplate() == null) {
        buildTemplate =
            new BuildFormEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
      } else if (md.bodyIndex() != null) {
        buildTemplate = new BuildEncodedTemplateFromArgs(md, encoder, queryMapEncoder, target);
      } else {
        buildTemplate = new BuildTemplateByResolvingArgs(md, queryMapEncoder, target);
      }
      if (md.isIgnored()) {
        result.put(md.configKey(), args -> {
          throw new IllegalStateException(md.configKey() + " is not a method handled by feign");
        });
      } else {
        //这里使用SynchronousMethodHandler.Factory factory和所有的Feign参数创建出了SynchronousMethodHandler用于处理对应的方法请求
        //这里的md.configKey() RandomService#getRandomNum()
        result.put(md.configKey(),
            factory.create(target, md, buildTemplate, options, decoder, errorDecoder));
      }
    }
    return result;
  }
}
```

这里记录一下需要注意的关键点

- `MethodMetadata`这里包含了很多方法信息，比如请求的url，返回参数，入参等等
- `md.configKey()`是一个字符串RandomService#getRandomNum() 就是这个样子
- 如餐Target也是一堆信息包括了@FeignClient中的一些参数信息和被@FeignClient注解注释的接口信息，比如类加载器、注解信息等等。

#### 生成动态代理

`  InvocationHandler handler = factory.create(target, methodToHandler)`在这个方法中就最终生成了动态代理，这里记录一下这段代码的关键点：

- InvocationHandler handler = factory.create(target, methodToHandler)这段代码生成的InvocationHandler是`FeignInvocationHandler`，代码如下

  ```java
  static class FeignInvocationHandler implements InvocationHandler {
  
    private final Target target;
    private final Map<Method, MethodHandler> dispatch;
  
    FeignInvocationHandler(Target target, Map<Method, MethodHandler> dispatch) {
      this.target = checkNotNull(target, "target");
      this.dispatch = checkNotNull(dispatch, "dispatch for %s", target);
    }
  
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      //equals、hashCode、toString几个方法的判断调用
      if ("equals".equals(method.getName())) {
        try {
          Object otherHandler =
              args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
          return equals(otherHandler);
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else if ("hashCode".equals(method.getName())) {
        return hashCode();
      } else if ("toString".equals(method.getName())) {
        return toString();
      }
  		//这里就是调用对应的方法处理请求了
      return dispatch.get(method).invoke(args);
    }
  ```

- MethodHandler是`SynchronousMethodHandler`Feign处理请求最终调用的就是`SynchronousMethodHandler#invoke()`方法

- target就是被@FeignClient注解注释的接口的相关信息

- 整个调用就是使用了JDK的动态代理方法

#### 对FeignClient发起调用

对FeignClient发起调用，实际上就会调用`FeignInvocationHandler#invoke()`方法，然后走到`SynchronousMethodHandler#invoke()`

```java
@Override
public Object invoke(Object[] argv) throws Throwable {
  //根据源数据信息和入参构建了RequestTemplate
  RequestTemplate template = buildTemplateFromArgs.create(argv);
  //这里构建了Feign相关的请求参数比如readTimeout、connectTimeout、时间单位等等 
  Options options = findOptions(argv);
  //重试器
  Retryer retryer = this.retryer.clone();
  while (true) {
    try {
      //这里从名字就可以看出来是执行Http请求并且Decode响应信息并返回
      return executeAndDecode(template, options);
    } catch (RetryableException e) {
      try {
        //这里应该跟重试相关
        retryer.continueOrPropagate(e);
      } catch (RetryableException th) {
        Throwable cause = th.getCause();
        if (propagationPolicy == UNWRAP && cause != null) {
          throw cause;
        } else {
          throw th;
        }
      }
      if (logLevel != Logger.Level.NONE) {
        logger.logRetry(metadata.configKey(), logLevel);
      }
      continue;
    }
  }
}
```

需要关注的点

- 根据方法的源数据和入参构建了`RequestTemplate`，在里面包含了发送这次Http调用所需要的基本上所有信息，包括方法的信息，编码，http请求等等最终比较直观和关键的信息就是这个 `GET /random/getRandomNum HTTP/1.1Binary data `这位了后面发送Http请求做准备。
- `Options options = findOptions(argv)`获取了Feign请求相关的一些参数读取超时时间、连接超时时间等等。

结下来执行进行Http调用的`executeAndDecode（）`方法

```java
Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
  //这里构建了要发送的Request请求 GET http://service-provider/random/getRandomNum HTTP/1.1 可以看出这里已经生成了一个完成的请求Url
  Request request = targetRequest(template);

  if (logLevel != Logger.Level.NONE) {
    logger.logRequest(metadata.configKey(), logLevel, request);
  }

  Response response;
  long start = System.nanoTime();
  try {
    //执行Http请求,注意这里的client是 LoadBalancerFeignClient
    response = client.execute(request, options);
    // ensure the request is set. TODO: remove in Feign 12
    response = response.toBuilder()
        .request(request)
        .requestTemplate(template)
        .build();
  } catch (IOException e) {
    if (logLevel != Logger.Level.NONE) {
      logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
    }
    throw errorExecuting(request, e);
  }
  long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);


  if (decoder != null)
    //解码并返回
    return decoder.decode(response, metadata.returnType());

  CompletableFuture<Object> resultFuture = new CompletableFuture<>();
  asyncResponseHandler.handleResponse(resultFuture, metadata.configKey(), response,
      metadata.returnType(),
      elapsedTime);

  try {
    if (!resultFuture.isDone())
      throw new IllegalStateException("Response handling not done");

    return resultFuture.join();
  } catch (CompletionException e) {
    .....省略一些异常处理代码
  }
}
```

`targetRequest(template)`方法对url进行了一系列的处理，主要做了如下这些事情

```java
Request targetRequest(RequestTemplate template) {
  for (RequestInterceptor interceptor : requestInterceptors) {
    interceptor.apply(template);
  }
  return target.apply(template);
}
```

- 首先使用拦截器处理RequestTemplate,不过这里没有加任何拦截器
- 调用`target.apply(template)`方法对RequestTemplate处理生成`Request`，其实就是对`RequestTemplate`中的Url字符串进行了一系列的处理最终生成的一个url串是`GET http://service-provider/random/getRandomNum HTTP/1.1`这里可以看出已经可以发送Http请求了，要请求的服务提供者也放到了url中，后面就通过Ribbon进行处理就可以了。

接下来调用了`LoadBalancerFeignClient#execute()`方法

```java
@Override
public Response execute(Request request, Request.Options options) throws IOException {
   try {
     //这里解析出了http的url的各个组成部分
      URI asUri = URI.create(request.url());
     //这里是要请求的服务提供者名称 "service-provider"
      String clientName = asUri.getHost();
     //这里把host从url中剔除出去http:///random/getRandomNum
      URI uriWithoutHost = cleanUrl(request.url(), clientName);
     //根据上面的参数生成了RibbonRequest,应该是给Ribbon用的
      FeignLoadBalancer.RibbonRequest ribbonRequest = new FeignLoadBalancer.RibbonRequest(
            this.delegate, request, uriWithoutHost);
			//这里从SpringClientFacotry中取出了一些配置信息，SpringClientFacotry在Ribbon那里看过，就是维护了每个服务都有一个ApplicationContext
      IClientConfig requestConfig = getClientConfig(options, clientName);
     //这里比较关键，这里就是ribbon中最重要的ILoadBalanced了，这里返回的是FeignLoadBalancer
      return lbClient(clientName)
            .executeWithLoadBalancer(ribbonRequest, requestConfig).toResponse();
   }
   catch (ClientException e) {
     ...省略一些异常代码
   }
}
```

总结一下，这里开始为使用Ribbon做准备，并且通过最终的`FeignLoadBalancer`这个负载均衡器进行了请求发送。

#### 与Feign与Ribbon整合逻辑

在`LoadBalancerFeignClient#execute()`方法中，最后调用了`LoadBalancerFeignClient#lbClient()`方法生成了`FeignLoadBalancer`

```java
private FeignLoadBalancer lbClient(String clientName) {
   return this.lbClientFactory.create(clientName);
}
```

上面这段代码最终调用到了`CachingSpringLoadBalancerFactory#create()`方法

```java
public FeignLoadBalancer create(String clientName) {
  //这里clientName传入的是service-provider
   FeignLoadBalancer client = this.cache.get(clientName);
   if (client != null) {
      return client;
   }
  //这里从SpringClientFacotry中拿出了客户端配置
   IClientConfig config = this.factory.getClientConfig(clientName);
  //这里从SpringClientFactory中生成了默认的ILoaderBalancer ZoneAwareLoadBalancer
   ILoadBalancer lb = this.factory.getLoadBalancer(clientName);
  //这里获取了EurekaServerIntrospector 
   ServerIntrospector serverIntrospector = this.factory.getInstance(clientName,
         ServerIntrospector.class);
  //这里根据上面生成的类生成了FeignLoadBalancer
   client = this.loadBalancedRetryFactory != null
         ? new RetryableFeignLoadBalancer(lb, config, serverIntrospector,
               this.loadBalancedRetryFactory)
         : new FeignLoadBalancer(lb, config, serverIntrospector);
  //放入缓存中
   this.cache.put(clientName, client);
  //返回客户端
   return client;
}
```

`CachingSpringLoadBalancerFactory`类中缓存了每个FeignClient对应的FeignLoadBalancer，这样以后每次发起Http调用就不用再每次生成了

```java
//Spring还自己实现了一些工具类，这个有点厉害呢ConcurrentReferenceHashMap
private volatile Map<String, FeignLoadBalancer> cache = new ConcurrentReferenceHashMap<>();
```

**关键点，其实Feign与Ribbon整合时，也是通过从SpringClientFacotry中取出了默认的ILoaderBalancer ZoneAwareLoadBalancer，这样Ribbon就跟Feign和Erucke整合起来了，也就是在`ZoneAwareLoadBalancer`的父类`DynamicServerListLoadBalancer`启动的时候会在构造类中调用`updateListOfServers`方法，这个方法就是通过Eureka-Client客户群与Eureka-Server进行通信获取了所有服务列表，然后放在了本地缓存中，并且启动一个`PollingServerListUpdater()` 任务，每30秒从Eureka中重新获取一下服务信息。**

#### Feign执行Http调用

```java
public T executeWithLoadBalancer(final S request, final IClientConfig requestConfig) throws ClientException {
        LoadBalancerCommand<T> command = buildLoadBalancerCommand(request, requestConfig);
    try {
      //注意这里调用了`LoadBalancerCommand`类的submit方法提交了发送http请求的命令
        return command.submit(
            new ServerOperation<T>() {
                @Override
                public Observable<T> call(Server server) {
                  //这里会生成最终的请求URL http://192.168.199.179:2200/random/getRandomNum
                    URI finalUri = reconstructURIWithServer(server, request.getUri());
                    S requestForServer = (S) request.replaceUri(finalUri);
                    try {
                      //调用FeignLoadBalancer#execute 方法发送Http请求
                        return Observable.just(AbstractLoadBalancerAwareClient.this.execute(requestForServer, requestConfig));
                    } 
                    catch (Exception e) {
                        return Observable.error(e);
                    }
                }
            })
            .toBlocking()
            .single();
    } catch (Exception e) {
      ...省略一些异常代码
    }
}
```

从上面的代码中可以知道，其实Feign发送Http请求的最后核心代码在`LoadBalancerCommand#submit()`方法中

```java
public Observable<T> submit(final ServerOperation<T> operation) {
    final ExecutionInfoContext context = new ExecutionInfoContext();
    
    if (listenerInvoker != null) {
        try {
            listenerInvoker.onExecutionStart();
        } catch (AbortExecutionException e) {
            return Observable.error(e);
        }
    }
		
  //根重试请求有关
    final int maxRetrysSame = retryHandler.getMaxRetriesOnSameServer();
    final int maxRetrysNext = retryHandler.getMaxRetriesOnNextServer();

    // Use the load balancer
    Observable<T> o = 
      //这里根据ILoaderBalancer 选择了Server
            (server == null ? selectServer() : Observable.just(server))
            .concatMap(new Func1<Server, Observable<T>>() {
                @Override
                // Called for each server being selected
                public Observable<T> call(Server server) {
                    context.setServer(server);
                    final ServerStats stats = loadBalancerContext.getServerStats(server);
                    
                    // Called for each attempt and retry
                    Observable<T> o = Observable
                            .just(server)
                            .concatMap(new Func1<Server, Observable<T>>() {
                                @Override
                                public Observable<T> call(final Server server) {
                                    context.incAttemptCount();
                                    loadBalancerContext.noteOpenConnection(stats);
                                    
                                    if (listenerInvoker != null) {
                                        try {
                                            listenerInvoker.onStartWithServer(context.toExecutionInfo());
                                        } catch (AbortExecutionException e) {
                                            return Observable.error(e);
                                        }
                                    }
                                    
                                    final Stopwatch tracer = loadBalancerContext.getExecuteTracer().start();
                                    
                                  //这里就执行了上面重写了的call方法，然后在方法返回结果后执行了一些后续的记录方法
                                    return operation.call(server).doOnEach(new Observer<T>() {
                                        private T entity;
                                        @Override
                                        public void onCompleted() {
                                            recordStats(tracer, stats, entity, null);
                                            // TODO: What to do if onNext or onError are never called?
                                        }

                                        @Override
                                        public void onError(Throwable e) {
                                            recordStats(tracer, stats, null, e);
                                            logger.debug("Got error {} when executed on server {}", e, server);
                                            if (listenerInvoker != null) {
                                                listenerInvoker.onExceptionWithServer(e, context.toExecutionInfo());
                                            }
                                        }

                                        @Override
                                        public void onNext(T entity) {
                                            this.entity = entity;
                                            if (listenerInvoker != null) {
                                                listenerInvoker.onExecutionSuccess(entity, context.toExecutionInfo());
                                            }
                                        }                            
                                        
                                        private void recordStats(Stopwatch tracer, ServerStats stats, Object entity, Throwable exception) {
                                            tracer.stop();
                                            loadBalancerContext.noteRequestCompletion(stats, entity, exception, tracer.getDuration(TimeUnit.MILLISECONDS), retryHandler);
                                        }
                                    });
                                }
                            });
                    
                    if (maxRetrysSame > 0) 
                        o = o.retry(retryPolicy(maxRetrysSame, true));
                    return o;
                }
            });
        
    if (maxRetrysNext > 0 && server == null) 
        o = o.retry(retryPolicy(maxRetrysNext, false));
    
    return o.onErrorResumeNext(new Func1<Throwable, Observable<T>>() {
        @Override
        public Observable<T> call(Throwable e) {
            if (context.getAttemptCount() > 0) {
                if (maxRetrysNext > 0 && context.getServerAttemptCount() == (maxRetrysNext + 1)) {
                    e = new ClientException(ClientException.ErrorType.NUMBEROF_RETRIES_NEXTSERVER_EXCEEDED,
                            "Number of retries on next server exceeded max " + maxRetrysNext
                            + " retries, while making a call for: " + context.getServer(), e);
                }
                else if (maxRetrysSame > 0 && context.getAttemptCount() == (maxRetrysSame + 1)) {
                    e = new ClientException(ClientException.ErrorType.NUMBEROF_RETRIES_EXEEDED,
                            "Number of retries exceeded max " + maxRetrysSame
                            + " retries, while making a call for: " + context.getServer(), e);
                }
            }
            if (listenerInvoker != null) {
                listenerInvoker.onExecutionFailed(e, context.toFinalExecutionInfo());
            }
            return Observable.error(e);
        }
    });
}
```

这里的一段代码是用RxJava写的，我以后也可以借鉴这段代码写RxJava相关的代码。

submit主要做了如下工作：

- 整理了一下重试相关的参数，这里的重试参数都是ribbon的
- 通过selectServer()负载均衡获得了一个要请求的Server信息，这后面的代码太长了，最终还是调用了ILoadBalancer的chooseServer(loadBalancerKey)方法获取的Server实例。
- 然后执行了call方法进行http请求
- 如果出现异常，那么在异常处理阶段根据重试参数的配置进行重试。

```java
@Override
public RibbonResponse execute(RibbonRequest request, IClientConfig configOverride)
      throws IOException {
  //重试相关的参数
   Request.Options options;
   if (configOverride != null) {
      RibbonProperties override = RibbonProperties.from(configOverride);
      options = new Request.Options(override.connectTimeout(connectTimeout),
            TimeUnit.MILLISECONDS, override.readTimeout(readTimeout),
            TimeUnit.MILLISECONDS, override.isFollowRedirects(followRedirects));
   }
   else {
      options = new Request.Options(connectTimeout, TimeUnit.MILLISECONDS,
            readTimeout, TimeUnit.MILLISECONDS, followRedirects);
   }
  //这里就是发送Http请求了
   Response response = request.client().execute(request.toRequest(), options);
   return new RibbonResponse(request.getUri(), response);
}
```

最终方法通过`FeignLoadBalancer#execute()`方法将请求发送出去，这里最后会通过`package feign # Client类` 中的默认实现类`class Default#execute`方法将请求发送出去

```java
@Override
public Response execute(Request request, Options options) throws IOException {
  //这里就是通过一个数据流把请求发送出去的，有点厉害
  HttpURLConnection connection = convertAndSend(request, options);
  return convertResponse(connection, request);
}
```

```java
//convertAndSend中的代码
connection.setDoOutput(true);
OutputStream out = connection.getOutputStream();
if (gzipEncodedRequest) {
  out = new GZIPOutputStream(out);
} else if (deflateEncodedRequest) {
  out = new DeflaterOutputStream(out);
}
try {
  out.write(request.body());
} finally {
  try {
    out.close();
  } catch (IOException suppressed) { // NOPMD
  }
}
```

网络通信这块的底层都快忘了，还是得看看。

#### Feign处理响应请求

在别的服务响应请求之后，在`SynchronousMethodHandler#executeAndDecode`方法中会进行对响应Response进行处理，最重要的就是对Response中的body进行Decode

```java
if (decoder != null)
  return decoder.decode(response, metadata.returnType());
```

这段代码就会根据方法需要的返回值将body decode成对应的类型。