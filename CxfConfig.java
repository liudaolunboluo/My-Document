package com.thunisoft.zgfy.dwjk.adapter;

import java.util.Set;

import javax.annotation.PostConstruct;
import javax.xml.ws.Endpoint;

import org.apache.cxf.Bus;
import org.apache.cxf.transport.servlet.CXFServlet;
import org.reflections.Reflections;
import org.reflections.scanners.FieldAnnotationsScanner;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.thunisoft.zgfy.dwjk.annotation.AutomaticPublishing;

/**
 * 
 * ClassName: CxfConfig
 * 
 * @Description: webservice配置类
 * @author zhangyunfan
 * @version 1.0
 *
 * @date 2020年2月14日
 */
@Configuration
public class CxfConfig implements ApplicationContextAware {

    private ConfigurableApplicationContext appContext;

    /** webservice扫描路径 */
    private final static String WEBSERVICE_SCAN_URL = "com.thunisoft.zgfy.dwjk.webservice";

    /** webservice根路径 */
    private final static String WEBSERVICE_URL = "/zgba/services/*";

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        appContext = (ConfigurableApplicationContext)applicationContext;
    }

    /** 必须要注入这个bus，否则service不会被注册 */
    @SuppressWarnings("unused")
    @Autowired
    private Bus bus;

    /**
     * CxfConfig
     * 
     * Description: 设置webservice根路径
     * 
     * @return ServletRegistrationBean
     * @throws
     * 
     *         @author zhangyunfan
     * @date 2020年2月27日
     */
    @Bean(name = "cxfServlet")
    public ServletRegistrationBean<CXFServlet> cxfServlet() {
        return new ServletRegistrationBean<CXFServlet>(new CXFServlet(), WEBSERVICE_URL);
    }

    /**
     * 
     * Description: 自动加载webservice并且发布 void
     * 
     * @throws
     * 
     *         @author zhangyunfan
     * @date 2020年2月14日
     */
    @PostConstruct
    public void releaseWebService() {
        Reflections reflections = new Reflections(
            new ConfigurationBuilder().forPackages(WEBSERVICE_SCAN_URL).addScanners(new FieldAnnotationsScanner()));
        Set<Class<?>> set = reflections.getTypesAnnotatedWith(AutomaticPublishing.class);
        for (Class<?> clazz : set) {
            Object webServiceOnject = appContext.getBean(clazz);
            AutomaticPublishing ap = webServiceOnject.getClass().getAnnotation(AutomaticPublishing.class);
            Endpoint.publish(ap.value(), webServiceOnject);
        }
    }

}
