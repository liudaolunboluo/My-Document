# Wrod操作神器——Aspose

##   一、Aspose介绍

 对word操作相信大家都不会默认，小到加盖人名章大到制作、编辑文书都需要用java代码去操作word，我们最常用的类库是Apache的POI，在大部分场景下POI都能满足我们的需求，但是在一些场景下比如替换书签、文件类型转换、docx操作等等用POI是无法满足我们的。这个时候就轮到了jacob登场了，jacob官方的解释是JavaCOMBridge,即java和com组件间的桥梁.像我们会用到的jacob.dll文件,这里说说为什么我们用java去操纵office(如:word)要使用com,而不直接使用java去做?首先,我们清楚office是建立在windows平台之上的,本身是一个软件,除了他自己提供的宏似乎没有什么能对他进行直接的操作;在windows平台上为了解决像这样的不同应用软件,通信缺乏通用api问题,推出了com的解决方案;总之，jacob是专门可以对word操作的，好处是功能比POI强大，缺点是必须需要部署环境要安装office而且还需要安装dll文件，公司大名鼎鼎的OnlinePaper就是基于Jacob开发的。

​	上面两种对word操作的解决方案都有各有千秋，今天要介绍的Aspose则是比上面两个更好的，他融合了POI和Jacob的优点，又没有他们的缺点。



## 二、集成Aspose

​	如果只是需要对word等文件做格式转换的话直接用[成都研发中心平台团队的转换组件](http://artery.thunisoft.com/posts/detail/0b6fe31d161c47ed8a18bd378d7a2ed7)即可，陈老师的组件就是基于Aspose开发的。如果需要更多的功能或者是自己项目的JDK版本低于1.8的可以自己集成Aspose然后实现。在公司Maven仓库直接搜索Aspose即可![](http://bed.thunisoft.com:9000/ibed/2019/09/10/036308a7bd9a4b29964affab9cdf4f1c.png)

选择适合自己项目JDK版本的版本加入到Maven依赖中。需要注意的是集成Aspose之后需要认证，具体过程可以参考[成都研发中心平台团队的转换组件](http://artery.thunisoft.com/posts/detail/0b6fe31d161c47ed8a18bd378d7a2ed7)的源码，这里不赘述。



### 三、Aspose简单应用

​	1、Document

Document是Aspose的核心类，一个Document表示一个文档，这个和POI和jacob一样。构造函数如下

```
Document()
创建一个空白的Word文档。

Document(java.lang.StringfileName)
从文件中打开现有文档。自动检测文件格式。

Document(java.lang.StringfileName, LoadOptions loadOptions)
从文件中打开现有文档。允许指定其他选项，例如加密密码。

Document(java.io.InputStreamstream)
从流中打开现有文档。自动检测文件格式。

Document(java.io.InputStreamstream, LoadOptions loadOptions)
从流中打开现有文档。允许指定其他选项，例如加密密码。

对文档操作完成之后调用save方法可以保存
save(java.io.OutputStream outputStream, SaveOptions saveOptions)	

```



 2、DocumentBuilder

DocumentBuilder提供插入文本，图像和其他内容的方法，指定字体，段落和格式。

例：在文档中添加一些文本，并使用DocumentBuilder将文本包含在书签中。

```java
DocumentBuilder builder = new DocumentBuilder（）;

builder.startBookmark（ “MyBookmark”）;
builder.writeln（“书签中的文字。”）;
builder.endBookmark（ “MyBookmark”）
```

例：插入HTML到文档中

```java
Document doc = new Document（）;
DocumentBuilder builder = new DocumentBuilder（doc）;

builder.insertHtml（“<P align ='right'> Paragraph right </ P>”+“<b>隐式段落</ b>”+“<div align ='center'> Div center </ div>” +“<h1 align ='left'>标题1左。</ h1>”）;

doc.save（getArtifactsDir（）+“DocumentBuilder.InsertHtml.doc”）;
```

例：演示如何使用具有默认格式的DocumentBuilder创建简单表格。

```java
Document doc = new Document（）;
DocumentBuilder builder = new DocumentBuilder（doc）;

//我们称这种方法开始构建表。
builder.startTable（）;
builder.insertCell（）;
builder.write（“第1行，单元格1内容。”）;

//构建第二个单元格
builder.insertCell（）;
builder.write（“第1行，Cell 2内容。”）;
//调用以下方法结束行并开始新行。
builder.endRow（）;

//构建第二行的第一个单元格。
builder.insertCell（）;
builder.write（“第2行，单元格1内容”）;

//构建第二个单元格。
builder.insertCell（）;
builder.write（“第2行，Cell 2内容。”）;
builder.endRow（）;

//表示我们已经完成了构建表。
builder.endTable（）;

//将文档保存到磁盘。
doc.save（getArtifactsDir（）+“DocumentBuilder.CreateSimpleTable.doc”）;
```

例：在指定书签插入图片

```java
      		DocumentBuilder builder = new DocumentBuilder(Document);
            builder.moveToBookmark("书签名字");
            builder.insertImage("图片的流");
```

三行代码解决图片替换书签的操作也就是法院业务上常用的加盖人名章。



3、Bookmark

Bookmark是word文档中书签对象，可以用来做书签替换操作，在制作文书的时候可以使用。

获得指定名称的书签：`    Bookmark bookmark = this.document.getRange().getBookmarks().get("书签名称");` `this.document`标识当前打开的文档。

给书签设置值或者名称：

```java
bookmark.setName("RenamedBookmark");
bookmark.setText("This is a new bookmarked text.");
```

 

短短两行代码就可以完成复杂的书签替换操作。



## 四、总结

Aspose不论是使用的便捷性和性能都是更胜POI和Jacob一筹，有关性能的研究大家都可以参考陈老师的[对比 Aspose 和 LibreOffice 转换 word 为 pdf 的效果](http://optimus.thunisoft.com/2019/05/06/qi-ta/aspose-dui-bi-libreoffice-de-zhuan-huan/)，本人实测利用Aspose加盖人名章比调用OnlinePaper加盖人名章要快大概一倍。

目前公司的项目中文书服务和监狱的[简版文书服务](http://artery.thunisoft.com/posts/detail/2b1c4d99730f4b118969b6c383e47712)都已经开始使用Aspose，大家都可以试试。







