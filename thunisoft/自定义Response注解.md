背景：项目的spring版本是2.5.6，这个版本的spring并没有ResponseBody注解，所以在每次写Springmvc接口的时候都需要手动把结果写入到response中，

```
@RequestMapping(value = "/t3c/test", method = RequestMethod.GET)
    public void test(HttpServletRequest req, HttpServletResponse resp) {
        JxjsHttpUtils.setResponseBody(resp, "helloWorld");
    }
```

就像这样，这样写一般没有问题，但是问题在于2019年了，spring 都到5.X了居然还用这种原始的方式？？于是我开始研究如何自己实现ResponseBody注解。



首先我想到的是用拦截器，继承HandlerInterceptorAdapter类，里面有三个方法：

   （1 ）preHandle (HttpServletRequest request, HttpServletResponse response, Object handle) 方法，顾名思义，该方法将在请求处理之前进行调用。SpringMVC 中的Interceptor 是链式的调用的，在一个应用中或者说是在一个请求中可以同时存在多个Interceptor 。每个Interceptor 的调用会依据它的声明顺序依次执行，而且最先执行的都是Interceptor 中的preHandle 方法，所以可以在这个方法中进行一些前置初始化操作或者是对当前请求的一个预处理，也可以在这个方法中进行一些判断来决定请求是否要继续进行下去。该方法的返回值是布尔值Boolean 类型的，当它返回为false 时，表示请求结束，后续的Interceptor 和Controller 都不会再执行；当返回值为true 时就会继续调用下一个Interceptor 的preHandle 方法，如果已经是最后一个Interceptor 的时候就会是调用当前请求的Controller 方法。

   （2 ）postHandle (HttpServletRequest request, HttpServletResponse response, Object handle, ModelAndView modelAndView) 方法，由preHandle 方法的解释我们知道这个方法包括后面要说到的afterCompletion 方法都只能是在当前所属的Interceptor 的preHandle 方法的返回值为true 时才能被调用。postHandle 方法，顾名思义就是在当前请求进行处理之后，也就是Controller 方法调用之后执行，但是它会在DispatcherServlet 进行视图返回渲染之前被调用，所以我们可以在这个方法中对Controller 处理之后的ModelAndView 对象进行操作。postHandle 方法被调用的方向跟preHandle 是相反的，也就是说先声明的Interceptor 的postHandle 方法反而会后执行，这和Struts2 里面的Interceptor 的执行过程有点类型。Struts2 里面的Interceptor 的执行过程也是链式的，只是在Struts2 里面需要手动调用ActionInvocation 的invoke 方法来触发对下一个Interceptor 或者是Action 的调用，然后每一个Interceptor 中在invoke 方法调用之前的内容都是按照声明顺序执行的，而invoke 方法之后的内容就是反向的。

   （3 ）afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handle, Exception ex) 方法，该方法也是需要当前对应的Interceptor 的preHandle 方法的返回值为true时才会执行。顾名思义，该方法将在整个请求结束之后，也就是在DispatcherServlet 渲染了对应的视图之后执行。这个方法的主要作用是用于进行资源清理工作的

好的，不出意外，里面没有有能完成ResponseBody注解功能的方法。此路不通。



山穷水尽疑无路，早上突然在论坛里看到姜老师的[SpringMvc自定义参数解析与返回值处理](http://artery.thunisoft.com/posts/detail/a1beeaa732944e6ea6d15592a8785670)，里面的内容完美匹配我的需求，于是准备动手开始干，结果发现里面要用到的接口和类都是spring 4.X的，此路还是不通。



最后想到了，如果在Controller的方法内直接返回一个字符串的话springmvc会跳转到字符串匹配的网页上去，于是绝望的我准备用一下最原始的方法，走debug方式一句一句代码的跟看下Springmvc是如何实现返回一个字符串或者其他能跳转到网页的，如果能搞清楚这个流程，那么是不是可以更改Spring的源码的来实现ResponseBody注解。

首先我们写一个测试的Controller：

```

```

   @RequestMapping(value = "/t3c/testaa", method = RequestMethod.GET)
    public String testaa(HttpServletRequest req, HttpServletResponse resp) {
        return "helloWorld";
    }



跟代码发现controller里的方法执行完后出来是在AnnotationMethodHandlerAdapter类的invokeHandlerMethod方法里面，看名字springmvc调用方法好像也是根据java反射来进行调用的。

```
protected ModelAndView invokeHandlerMethod(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {

        try {
            ServletHandlerMethodResolver methodResolver = getMethodResolver(handler);
            Method handlerMethod = methodResolver.resolveHandlerMethod(request);
            ServletHandlerMethodInvoker methodInvoker = new ServletHandlerMethodInvoker(methodResolver);
            ServletWebRequest webRequest = new ServletWebRequest(request, response);
            ExtendedModelMap implicitModel = new BindingAwareModelMap();

            Object result = methodInvoker.invokeHandlerMethod(handlerMethod, handler, webRequest, implicitModel);
            ModelAndView mav
                = methodInvoker.getModelAndView(handlerMethod, handler.getClass(), result, implicitModel, webRequest);
            methodInvoker.updateModelAttributes(handler, (mav != null ? mav.getModel() : null), implicitModel,
                webRequest);
            return mav;
        } catch (NoSuchRequestHandlingMethodException ex) {
            return handleNoSuchRequestHandlingMethod(ex, request, response);
        }
    }
```

` Object result = methodInvoker.invokeHandlerMethod(handlerMethod, handler, webRequest, implicitModel);
            ModelAndView mav`这句代码就是在执行springmvc里的方法的代码，result就是执行出来的返回值。



```
 ModelAndView mav
                = methodInvoker.getModelAndView(handlerMethod, handler.getClass(), result, implicitModel, webRequest);
            methodInvoker.updateModelAttributes(handler, (mav != null ? mav.getModel() : null), implicitModel,
                webRequest);
```

这两句代码是根据返回值构建ModelAndView对象，这个方法执行完了之后就到了DispatcherServle类的doDispatch 方法，直接看关键代码

```
mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

				// Actually invoke the handler.
				HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());
				mv = ha.handle(processedRequest, response, mappedHandler.getHandler());

				// Do we need view name translation?
				if (mv != null && !mv.hasView()) {
					mv.setViewName(getDefaultViewName(request));
				}

				// Apply postHandle methods of registered interceptors.
				if (interceptors != null) {
					for (int i = interceptors.length - 1; i >= 0; i--) {
						HandlerInterceptor interceptor = interceptors[i];
						interceptor.postHandle(processedRequest, response, mappedHandler.getHandler(), mv);
					}
				}
			}
			catch (ModelAndViewDefiningException ex) {
				logger.debug("ModelAndViewDefiningException encountered", ex);
				mv = ex.getModelAndView();
			}
			catch (Exception ex) {
				Object handler = (mappedHandler != null ? mappedHandler.getHandler() : null);
				mv = processHandlerException(processedRequest, response, handler, ex);
				errorView = (mv != null);
			}

			// Did the handler return a view to render?
			if (mv != null && !mv.wasCleared()) {
				render(mv, processedRequest, response);
				if (errorView) {
					WebUtils.clearErrorRequestAttributes(request);
				}
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("Null ModelAndView returned to DispatcherServlet with name '" +
							getServletName() + "': assuming HandlerAdapter completed request handling");
				}
			}

```



```
				HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());
				mv = ha.handle(processedRequest, response, mappedHandler.getHandler());
```

这两句代码是去获取HandlerAdapter的，这里获取的HandlerAdapter肯定就是刚刚的AnnotationMethodHandlerAdapter这个，然后执行handle方法，会返回一个ModelAndView类型的mv对象，这个mv就是刚刚AnnotationMethodHandlerAdapter的invokeHandlerMethod方法返回值，然后继续跟代码，发现断点经过render(mv, processedRequest, response);这句代码之后网页就变成404了，后台进就报错XXX.jsp not found ，说明这个方法就是跳转页面的方法，距离真相越来越近了。F5进入render方法，然后中间代码就不上了，最后的结果是在InternalResourceView类的renderMergedOutputModel方法，直接看最关键的一句`	rd.forward(requestToExpose, response);` 

朋友们！这个forward眼熟吗，就是跳转啊，就是在这里实现的跳转到网页啊。那么理清楚流向之后，我们就可以很简单的，来自己写ResponseBody注解了。我们应该在AnnotationMethodHandlerAdapter的invokeHandlerMethod方法里动手脚。再次debug我把结果写在了response里的controller之后发现在这个地方如果result为null的话返回的mv就是null，那么接下来就不会走forward的逻辑，很好，那么我们就动手吧。

```
 			Object result = methodInvoker.invokeHandlerMethod(handlerMethod, handler, webRequest, implicitModel);
            ModelAndView mav
                = methodInvoker.getModelAndView(handlerMethod, handler.getClass(), result, implicitModel, webRequest);
            methodInvoker.updateModelAttributes(handler, (mav != null ? mav.getModel() : null), implicitModel,
                webRequest);
            // By zhangyunfan 自定义ResponseBody注解
            ResponseBody res = handlerMethod.getAnnotation(ResponseBody.class);
            if (res != null) {
                mav = null;
                JxjsHttpUtils.setResponseBody(response, result);
            }
```

这里能拿到我要执行的目标方法和Response，满足了我的所有需要，从方法内获取我自己定义的ResponseBody注解，如果这个方法标明了ResponseBody注解的话，就把mv设置为null，很明显这里不再需要去forward跳转了，然后还是用我一开始用到的方法把我们的返回值写到可爱的Response里就可以了。



### 总结：

从上文我们从结果反推代码，后来谷歌之后发现 DispatcherServlet是前置控制器，配置在web.xml文件中的。拦截匹配的请求，Servlet拦截匹配规则要自己定义，把拦截下来的请求，依据相应的规则分发到目标Controller来处理，是配置spring MVC的第一步。

DispatcherServlet是前端控制器设计模式的实现，提供Spring Web MVC的集中访问点，而且负责职责的分派，而且与Spring IoC容器无缝集成，从而可以获得Spring的所有好处。而doDispatch方法主要用作职责调度工作，本身主要用于控制流程，主要职责如下：

1、文件上传解析，如果请求类型是multipart将通过MultipartResolver进行文件上传解析；

2、通过HandlerMapping，将请求映射到处理器（返回一个HandlerExecutionChain，它包括一个处理器、多个HandlerInterceptor拦截器）；

3、通过HandlerAdapter支持多种类型的处理器(HandlerExecutionChain中的处理器)；

4、调用HandlerExecutionChain的interceptor和handler

5、解析视图、处理异常，渲染具体的视图等；

这里就反推出Springmvc的底层结构了，这里就不再赘述了，接下来我会自己再实现RequestBody注解，敬请期待！