# Ruby的Web框架Sinatra

先安装Ruby，直接在[官网](https://rubyinstaller.org/downloads/) 上选择合适的版本下载即可，下载完成之后打开命令行输入`gem install sinatra ` 注意由于Ruby的镜像网站被墙了，用命令`gem sources -l` 可以查看当前镜像网站，可以看到这个网站没有梯子的话是不能访问的，于是我们用命令`gem sources --remove https://ruby.org/` 删除这个地址，然后`gem sources --add https://gems.ruby-china.com/` 添加国内的Ruby镜像地址，有梯子的同学可以跳过。



安装完Sinatra之后，我们新建一个文本文件，输入下列代码:

```ruby
# frozen_string_literal: true

require 'sinatra'
require 'sinatra/base'

configure do
  set :port, '8080'
end

  
get '/' do
   'Hello world!'
end
```



然后点击保存，后缀名改为`.rb`,然后打开命令行`ruby XX.rb` 就可以启动Sinatra了，然后用浏览器访问`localhost:8080` 就可以看到浏览器显示了刚刚配置的Hello World了，是不是很简单？十多行代码就可以完成一个web服务器，更多配置可以去[Sinatra官方文档](http://sinatrarb.com/intro-zh.html)上发现，注意：在configure里面如果没有`set: Bind` 配置为当前所在IP的话，那么外部机器是无法访问你的Sinatra服务器的