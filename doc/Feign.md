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

**上面说的默认组件都可以通过自定义进行覆盖，从而实现自定义FeignClient，这些组件都是在哪里定义的呢，实在spring-cloud-netflix-core这个工程中，这个工程就是SpringCloud整合了Eureka、Ribbon、Feign、Zuul等等的胶水工程，里面有很多AutoConfiguration和Configuration类。上面说的这些组件就是在**

## Feign的配置

之前都没怎么研究过Feign配置，Feign配置主要分三块，普通参数配置、压缩配置、日志配置

这里后面在补充吧，这个看看别人的博客吧，应该有很多配置直接贴上来，捏哈哈哈

