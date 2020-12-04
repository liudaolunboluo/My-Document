前情提要：[老项目的倔强——自己实现ResponseBody注解](http://artery.thunisoft.com/posts/detail/654e3c74f2dd415b931d3c00aa90b5d8)

​		 [老项目的倔强——自己实现RequestBody注解](http://artery.thunisoft.com/posts/detail/d99b479e798540f3b346e7c23aa0ce04)

上次通过阅读Spring的源码我们成功实现了ResponseBody注解和RequestBody注解，然后呢，我发现我也许还能做更多，然后就找到了PathVariable这个注解，这个spring3.0之后新增的注解在SpringMVC 向 REST 目标挺进发展过程中具有里程碑的意义，然后我们现在的spring版本是2.5.6，我也想向Rest目标挺近然后现在公司接口规范也是要求Rest风格，怎么办？自己动手吧。



首先我们得搞清楚PathVariable注解能干什么？ PathVariable注解可以将 **URL** 中占位符参数绑定到控制器处理方法的入参中：URL 中的 {**xxx**} 占位符可以通过@PathVariable(“**xxx**“) 绑定到操作方法的入参中。所以我们从他能实现的功能入手来想想怎么分步奏实现这个注解。

#### 1、匹配URL

毫无疑问这个是第一步，spring2.5.6是不支持Rest风格的接口的，所以我们得改造一下。首先我在Spring4.X上实验之后得出了spring mvc中的rest接口规范：如果有两个接口/a/{b}  /a/{c} 也就是很容易混淆的rest接口，spring会直接抛出异常Ambiguous handler methods mapped for；先级是非rest的接口大于rest风格的接口，/hello/{id} /hello/zyf 如果浏览器是/hello/zyf 那么访问的是第二个接口；所以我们在匹配URL的时候也要根据这两个规范或者说约定来。

#### 2、匹配URL中的值设置给方法的参数

根据PathVariable注解设置参数列表的值,根据PathVariable注解里的value来匹配RequestMapping里参数的位置然后直接到请求参数里获取



到了这里我们大体清楚了如何实现这个注解，那我们就开始吧。



从前面的分析可以得出系统里所有的SpringMvc的请求都会先在DispatcherServlet的doDispatch方法里进行处理然后分配给目标Controller的目标方法，那么我们先来跟一下DispatcherServlet的源码，我们先新建一个Rest风格的接口，然后用浏览器访问

```
@RequestMapping(value = "/{name}/t3c/{id}", method = RequestMethod.GET)
    @ResponseBody
    public String test(@PathVariable("id") String id, @PathVariable("name") String name) {
        return "HelloWord";
    }
```

毫无疑问浏览器会报404，那么我们进入DispatcherServlet里看看吧。在前面的研究里我们知道了mappedHandler这个变量里面就有我们的目标Controller对象，所以在DispatcherServlet源码里*mappedHandler = getHandler(processedRequest, false);* 这一句代码一定就是取根据请求路径寻找我们的Controller和方法的方法了，为了简单验证我访问了上面的rest风格的接口和其他接口发现，其他接口这里出来的结果是有值的，而rest接口这里出来的数null，那么初步证明了我们的猜想，让我们进去看看吧，*getHandler* 方法内部一步一步的进入的话我们最后会到一个*AbstractUrlHandlerMapping* 的*lookupHandler* 方法里来，我们来看看代码：

```
	protected Object lookupHandler(String urlPath, HttpServletRequest request) throws Exception {
		// Direct match?
		//handlerMap是spring自己封装的一个路径和类映射的Map，先用原路径匹配
		Object handler = this.handlerMap.get(urlPath);
		if (handler != null) {
			validateHandler(handler, request);
			return buildPathExposingHandler(handler, urlPath);
		}
		// Pattern match?
		//如果全路径没有匹配的就用正则匹配，就是类似于*这种
		String bestPathMatch = null;
		for (Iterator it = this.handlerMap.keySet().iterator(); it.hasNext();) {
			String registeredPath = (String) it.next();
			if (getPathMatcher().match(registeredPath, urlPath) &&
					(bestPathMatch == null || bestPathMatch.length() < registeredPath.length())) {
				bestPathMatch = registeredPath;
			}
		}
		if (bestPathMatch != null) {
			handler = this.handlerMap.get(bestPathMatch);
			validateHandler(handler, request);
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(bestPathMatch, urlPath);
			return buildPathExposingHandler(handler, pathWithinMapping);
		}
		// No handler found...
		//如果全路径和通配符都没有的话那就是没有
		return null;
	}
```

中文注释是我自己加的，意思和英文注释大差不差，看到这里我很兴奋，说明我找对地方了，spring就是在这里去对访问的url和系统内的controller进行匹配的，这里只有两种匹配方式全路径和正则，那我们就自己动手给他加上第三种方式——Rest风格方式！

```
  		// rest风格？
        for (Object key : this.handlerMap.keySet()) {
            String mappedPath = String.valueOf(key);
            if (mappedPath.indexOf("{") != -1 && mappedPath.indexOf("}") != -1) {
                // 算法：用/分割，每个没有{}的下标应该一一对应
                String[] mappedPathArr = mappedPath.split("/");
                String[] lookupPathArr = urlPath.split("/");
                if (mappedPathArr.length != lookupPathArr.length) {
                    continue;
                }
                boolean flag = true;
                for (int i = 0; i < mappedPathArr.length; i++) {
                    // 没有{}的但是内容和访问路径不一致的不通过
                    if (mappedPathArr[i].indexOf("{") == -1 && mappedPathArr[i].indexOf("}") == -1
                        && !mappedPathArr[i].equals(lookupPathArr[i])) {
                        flag = false;
                    }
                }
                // 如果有两个相像的rest风格接口且不是通配符抛异常
                if (flag && handler != null && mappedPath.indexOf("*") == -1) {
                    throw new java.lang.IllegalStateException("Ambiguous handler methods mapped for " + urlPath);
                }
                if (flag) {
                    handler = this.handlerMap.get(key);
                }
            }
        }
        if (handler != null) {
            validateHandler(handler, request);
            return buildPathExposingHandler(handler, urlPath);
        }
```

代码大家凑合着看一下这个是我自己实现的匹配算法（Hack 也许有更好的比如正则等我暂时没有想到），这里加上之后我们重启再访问刚刚的接口，发现还是404？？，我有点不敢相信，难道Spring在后面的代码里还要去匹配一次不成？ 为了证明我的猜想我debug继续跟代码，发现果然Spring还会认证一次，是在对应的类里面寻找方法的时候对应，上面的只是找到了Controller，后面还要根据URL去Controller里面找RequestMapping注解标识了的方法，是在*AnnotationMethodHandlerAdapter* 类的*resolveHandlerMethod* 方法内

```
   for (String mappedPath : mappingInfo.paths) {
                        if (isPathMatch(mappedPath, lookupPath)) {
                            if (checkParameters(mappingInfo, request)) {
                                match = true;
                                targetPathMatches.put(mappingInfo, mappedPath);
                            } else {
                                break;
                            }
                        }
                    }
```

这里*isPathMatch* 方法内还要认证一次，我们进去瞅瞅。

```
private boolean isPathMatch(String mappedPath, String lookupPath) {
			if (mappedPath.equals(lookupPath) || pathMatcher.match(mappedPath, lookupPath)) {
				return true;
			}
			boolean hasSuffix = (mappedPath.indexOf('.') != -1);
			if (!hasSuffix && pathMatcher.match(mappedPath + ".*", lookupPath)) {
				return true;
			}
			return (!mappedPath.startsWith("/") &&
					(lookupPath.endsWith(mappedPath) || pathMatcher.match("/**/" + mappedPath, lookupPath) ||
							(!hasSuffix && pathMatcher.match("/**/" + mappedPath + ".*", lookupPath))));
		}
```

可以看到这里和上面的*lookupHandler* 方法其实大差不差，也是全路径匹配和通配符匹配，那么我们在这里也加上第三种认证方式——rest方式，代码就不上了和上面的几乎一模一样。改完之后重启访问我们的Rest风格接口，发现能正确找到我们的接口了！



至此第一步匹配路径已经完成了，我们来完成第二步匹配参数列表，一样的我们可以复用我们之前的研究，这个匹配参数列表的功能实际有点像RequestBody的功能，所以我们去实现RequestBody的类里面去看看能不能加点代码就实现这个注解呢，我们是在*HandlerMethodInvoker* 的*resolveHandlerArguments* 方法内实现的RequestBody注解的，这个方法是去获取要运行的方法的参数的，所以我们可以就在这里修改，首先在代码里原来遍历参数注解的地方加上我们的PathVariable注解，话不多说直接上代码

```
		// 遍历这个参数的所有注解
            for (int j = 0; j < paramAnns.length; j++) {
                Object paramAnn = paramAnns[j];
                // 判断是否标记了RequestBody注解
                if (RequestBody.class.isInstance(paramAnn)) {
                    hashRequestBody = true;
                }
                // 是否启用了PathVariable注解，启用了的话记录PathVariable的值
                if (PathVariable.class.isInstance(paramAnn)) {
                    hasPathVariable = true;
                    PathVariable pv = (PathVariable)paramAnn;
                    pathVariableName = pv.value();
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
```

```
 				// 如果是rest风格
                if (hasPathVariable) {
                    // 获取到请求地址和requestmapping地址，然后做比对来设置好参数
                    String lookupUrl
                        = (String)webRequest.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                            RequestAttributes.SCOPE_REQUEST);
                    RequestMapping mapping = AnnotationUtils.findAnnotation(handlerMethod, RequestMapping.class);
                    String mappringUrl = mapping.value()[0];
                    String[] mappedPathArr = mappringUrl.split("/");
                    String[] lookupPathArr = lookupUrl.split("/");
                    // 获取{param}的下标，然后去真实的访问地址获取这个下标的值并且赋值。
                    for (int j = 0; j < mappedPathArr.length; j++) {
                        if (StringUtils.equals(mappedPathArr[j], "{" + pathVariableName + "}")) {
                            argValue = lookupPathArr[j];
                        }
                    }
                } 
```

大家自己看下代码就可以了，然后我们访问刚刚的接口http://localhost:8081/jxjs/mvc/jack/t3c/123 发现后台接口![](http://bed.thunisoft.com:9000/ibed/2019/07/23/040202afd2cb41529f79fcd7df974d21.png)

好了，现在的Spring版本是2.5.6，我们可以在这个版本上书写Rest风格代码啦！