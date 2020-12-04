前情提要：[老项目的倔强——自己实现ResponseBody注解](http://artery.thunisoft.com/posts/detail/654e3c74f2dd415b931d3c00aa90b5d8)

前面提到了在`AnnotationMethodHandlerAdapter`的`invokeHandlerMethod`方法里的`Object result = methodInvoker.invokeHandlerMethod(handlerMethod, handler, webRequest, implicitModel);`这一句代码里来执行的Controller里面的方法的，这个方法在`HandlerMethodInvoker`类里面，那么如果我们要实现RequestBody注解一定就是要在这个类里面动手脚的，我们先进去看一下

```
  public final Object invokeHandlerMethod(Method handlerMethod, Object handler, NativeWebRequest webRequest,
        ExtendedModelMap implicitModel) throws Exception {

        Method handlerMethodToInvoke = BridgeMethodResolver.findBridgedMethod(handlerMethod);
        try {
            boolean debug = logger.isDebugEnabled();
            for (Method attributeMethod : this.methodResolver.getModelAttributeMethods()) {
                Method attributeMethodToInvoke = BridgeMethodResolver.findBridgedMethod(attributeMethod);
                Object[] args = resolveHandlerArguments(attributeMethodToInvoke, handler, webRequest, implicitModel);
                if (debug) {
                    logger.debug("Invoking model attribute method: " + attributeMethodToInvoke);
                }
                Object attrValue = doInvokeMethod(attributeMethodToInvoke, handler, args);
                String attrName = AnnotationUtils.findAnnotation(attributeMethodToInvoke, ModelAttribute.class).value();
                if ("".equals(attrName)) {
                    Class resolvedType
                        = GenericTypeResolver.resolveReturnType(attributeMethodToInvoke, handler.getClass());
                    attrName
                        = Conventions.getVariableNameForReturnType(attributeMethodToInvoke, resolvedType, attrValue);
                }
                implicitModel.addAttribute(attrName, attrValue);
            }
            Object[] args = resolveHandlerArguments(handlerMethodToInvoke, handler, webRequest, implicitModel);
            if (debug) {
                logger.debug("Invoking request handler method: " + handlerMethodToInvoke);
            }
            return doInvokeMethod(handlerMethodToInvoke, handler, args);
        } catch (IllegalStateException ex) {
            // Throw exception with full handler method context...
            throw new HandlerMethodInvocationException(handlerMethodToInvoke, ex);
        }
    }
    
```

看到`doInvokeMethod(handlerMethodToInvoke, handler, args);`进入这个方法就可以发现这里会调用java反射的invoke方法，args就是执行的方法里参数列表里定义的参数了，那么上面的` Object[] args = resolveHandlerArguments(handlerMethodToInvoke, handler, webRequest, implicitModel);`肯定就是去获取参数的方法啦。进去看源码，远吗太长了这里就不展示了就说关键部分，在源码里获取参数值的核心方法是`resolveHandlerArguments` 在这个方法里我们可以找到

``` 
if (RequestParam.class.isInstance(paramAnn)) {
                    RequestParam requestParam = (RequestParam)paramAnn;
                    paramName = requestParam.value();
                    paramRequired = requestParam.required();
                    break;
                } 
```

这么一段代码，这个是spring的RequestParam注解，很好！说明我们已经接近真相了。

在下面的代码里可以找到这一行`argValue = resolveCommonArgument(methodParam, webRequest);` 这个看名字和结合下面的代码这里就是去找参数的，我们再进去发现最后还是在`AnnotationMethodHandlerAdapter`类中的`resolveStandardArgument`方法内来获取参数的，我们看下这个方法

```
@Override
        protected Object resolveStandardArgument(Class parameterType, NativeWebRequest webRequest) throws Exception {

            HttpServletRequest request = (HttpServletRequest)webRequest.getNativeRequest();
            HttpServletResponse response = (HttpServletResponse)webRequest.getNativeResponse();

            if (ServletRequest.class.isAssignableFrom(parameterType)) {
                return request;
            } else if (ServletResponse.class.isAssignableFrom(parameterType)) {
                this.responseArgumentUsed = true;
                return response;
             
```

这里只是截取了一部分代码，很明显的看到这里如果参数类型是Request和Response的话这里就这样返回了，那么说明我们已经找对地方了！顺便吐槽一下spring早期的代码确实有点不怎么优雅。那么我们回到`HandlerMethodInvoker`的`resolveHandlerArguments` 方法中，这里应该是我们动手脚的地方。其实在`AnnotationMethodHandlerAdapter`类中的`resolveStandardArgument`方法中动手脚也可以但是我选择了前者，主要是里面有判断RequestParam注解的地方，我们还是要和spring保持一致。先新建一个RequestBody注解，然后再`HandlerMethodInvoker`的`resolveHandlerArguments`  中增加如下代码：

```  
 Object[] paramAnns = methodParam.getParameterAnnotations();
            // 遍历这个参数的所有注解
            for (int j = 0; j < paramAnns.length; j++) {
                Object paramAnn = paramAnns[j];
                // 判断是否标记了RequestBody注解
                if (RequestBody.class.isInstance(paramAnn)) {
                    hashRequestBody = true;
                }
                if (RequestParam.class.isInstance(paramAnn)) {
                    RequestParam requestParam = (RequestParam)paramAnn;
                    paramName = requestParam.value();
                    paramRequired = requestParam.required();
                    break;
                } else if (ModelAttribute.class.isInstance(paramAnn)) {
                    ModelAttribute attr = (ModelAttribute)paramAnn;
                    attrName = attr.value();
                }
            }
            if (paramName != null && attrName != null) {
                throw new IllegalStateException("@RequestParam and @ModelAttribute are an exclusive choice -"
                    + "do not specify both on the same parameter: " + handlerMethod);
            }

            Class paramType = methodParam.getParameterType();

            if (paramName == null && attrName == null) {
                Object argValue = null;
                // 如果有RequestBody注解
                if (hashRequestBody) {
                    HttpServletRequest request = (HttpServletRequest)webRequest.getNativeRequest();
                    try {
                        // 从Request里获取参数
                        argValue = JSONObject.parseObject(JxjsHttpUtils.getRequestBody(request), paramType);
                    } catch (Exception e) {
                        logger.error("接收参数失败", e);
                    }
                } else {
                    // 如果没有RequestBody注解就该怎么获取参数就怎么获取参数
                    argValue = resolveCommonArgument(methodParam, webRequest);
                }
```

思想就是在遍历这个方法的参数的注解的时候判断有没有RequestBody注解，如果这个参数存在这个注解那么这个参数就直接从Request中获取值，先从流中拿到json然后用fastjson转换成参数列表里声明的类的类型，就可以了。