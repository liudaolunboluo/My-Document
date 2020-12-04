# VERSION 1 - EDITION 1
# Author: zhangyunfan

#指定基础镜像,2.7.3-jdk6-1这个版本的镜像是jdk1.6
FROM registry.thunisoft.com:5000/base/tas:2.7.3-jdk6-1
#指定作者
MAINTAINER zhangyunfan zhangyunfan@thunisoft.com

#为镜像指定标签
LABEL acloud.description="this is jxjs image,made by zhangyunfan." \
      acloud.env.CONFIG_LOGBACK_COMMON_LEVEL="error" \
      acloud.env.CONFIG_LOGBACK_ROOT_LEVEL="error" \
      acloud.env.CONFIG_LOGBACK_STDOUT_LEVEL="off" \
      acloud.env.CONFIG_DEBUG="true" \
      acloud.env.JVM_XMS="1024m" \
      acloud.env.JVM_XMX="2048m" \
      acloud.env.JVM_MAXNEWSIZE="256m" \
      acloud.env.JVM_PERMSIZE="64m" \
      acloud.env.JVM_MAXPERMSIZE="512m" \
      acloud.env.appconfigServerUrl="http://tap-dev.thunisoft.com/appconfig" \
      acloud.env.corp="2400" \
      acloud.env.appname="jxjs" \
      acloud.env.pinpoint.applicationName="jxjs" 
    
#设置环境变量 
ENV CONFIG_PATH=/opt/tas/webapps/jxjs/WEB-INF/classes/props/config.properties \
    CONFIG_LOGBACK_COMMON_LEVEL="error" \
    CONFIG_LOGBACK_ROOT_LEVEL="error" \
    CONFIG_LOGBACK_STDOUT_LEVEL="on" \
    CONFIG_DEBUG="false"
     
# 把jxjs的war拷贝到镜像中
ADD war/jxjs-3.1.war  /opt/tas/webapps/jxjs.war

#装上Arthas便于排查问题
COPY --from=hengyunabc/arthas:latest /opt/arthas /opt/arthas

# 声明jxjs的数据卷，此卷用来存储用户上传的图片，文件等数据
VOLUME ["/opt/tas/logs/"]

#矫正宿主机时间和license服务器保持一致
RUN  /bin/cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo 'Asia/Shanghai' >/etc/timezone

#暴漏容器运行时的监听端口给外部
EXPOSE 8080

#启动时执行的默认命令
ENTRYPOINT /acloud-decompress.sh && /acloud-entrypoint.sh && /opt/tas/bin/StartTAS.sh && /usr/sbin/sshd –D
