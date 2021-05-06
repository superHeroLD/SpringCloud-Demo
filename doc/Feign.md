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

- 生成了`FeignClientFactoryBean`FeignClient的FactoryBean，这里猜测一下，应该是后面用户在代码中自动注入对应的FeignClient时，Spring就会通过对应的FactoryBean的`getObject()`方法生成对应的动态代理注入到代码中。在实际使用的过程中，也是使用的动态代理进行Http调用。
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
   FeignContext context = beanFactory != null
         ? beanFactory.getBean(FeignContext.class)
         : applicationContext.getBean(FeignContext.class);
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

这段代码简单的过一遍，就是FactoryBean根据FeignContext构建不同类型的FeignClient。比如`LoadBalancerFeignClient`、`FeignBlockingLoadBalancerClient`等等。

