# 优化代码遇到的上传文件为空的问题，引出的FileInputstream问题

## 1、起因

最近在做新需求的时候看到一段老代码，大概就是：

```java
  public ResponseInfoVo<String> upload(@RequestParam("file") MultipartFile file) {
    ...
                String fileMd5 = FileUtils.getFileMD5String(file.getInputStream());
                hdfsClient.uploadLocalFile2HDFS(file.getInputStream(), hdfsFile);
    ...
    }
```

很简单，就是上传数据集文件到hdfs中

当然了这段代码还是有问题，他的流没有关闭，作为资深代码洁癖患者，这当然不能忍了，于是我很快改成了如下代码:

```java
public ResponseInfoVo<String> upload(@RequestParam("file") MultipartFile file) {
  ...
  			try(InputStream inputStream = file.getInputStream()){
            String fileMd5 = FileUtils.getFileMD5String(inputStream);
            hdfsClient.uploadLocalFile2HDFS(inputStream, hdfsFile);
        }        
    ...
    }
```

改好了之后光速推到测试环境，结果界面开始报错，然后发现是获取csv文件内容的接口返回空了，于是上到hdfs上一看，刚刚上传的文件0kb。

这里我就傻了，我只是改成了try with source语法啊也妹做什么其他的啊，怎么改了之后上传文件就没了呢？

于是我本地debug了一下，代码在`  String fileMd5 = FileUtils.getFileMD5String(inputStream);`这一行的时候，我调用了`available`发现这一行执行之前是有值的，这一行执行之后就变成0了，所以在下一行上传文件到hdfs的时候肯定就为0kb了。

到这里，其实我最疑惑的是：为什么修改代码之前没有问题呢？都调用的`file.getInputStream()`，按理来说第二次`file.getInputStream()`时候也应该为0了？于是我debug了一下，发现两次执行`file.getInputStream()`出来的InputStream的内存地址值还真不一样，于是我就翻了一下`MultipartFile`的源码，发现原来另有玄机：

```java
public InputStream getInputStream() throws IOException {
        if (!this.isInMemory()) {
            return new FileInputStream(this.dfos.getFile());
        } else {
            if (this.cachedContent == null) {
                this.cachedContent = this.dfos.getData();
            }

            return new ByteArrayInputStream(this.cachedContent);
        }
    }
```

原来，每次`getInputStream`但是返回一个`new FileInputStream`或者`new ByteArrayInputStream`，所以每次调用返回的对象都不是同一个，这也解释了为什么之前的代码没有问题。

然后就是为什么调用了`FileUtils.getFileMD5String`之后，我们的流会变成空的？

`FileUtils.getFileMD5String`就是获取一个文件MD5字符串的方法， 我一行一行的debug之后发现，我的流是在这几行代码之后变成0的：

```java
public static MessageDigest updateDigest(final MessageDigest digest, final InputStream data) throws IOException {
        final byte[] buffer = new byte[STREAM_BUFFER_LENGTH];
        int read = data.read(buffer, 0, STREAM_BUFFER_LENGTH);

        while (read > -1) {
            digest.update(buffer, 0, read);
            read = data.read(buffer, 0, STREAM_BUFFER_LENGTH);
        }

        return digest;
    }
```

也就是这里执行了read方法，把文件内容读取到了bffer数组之中所以之后这个流就为空了，我们知道`FileInputStream`类似于一个自来水管道，另外一边就是一个自来水厂，管道本身不存放内容他只是搬运工，这里执行read方法之后就把内容搬空了，所以我们的流就为空了，那么这究竟是如何实现的？其

实问题到了这里就已经破案了，由于`FileUtils.getFileMD5String`调用了read方法，把流的使用完成了，如何还用同样的流来上传文件，肯定会造成文件0KB的，但是本着打破砂锅问到底的精神，我们可以一起看一下read的源码来看下为什么我们调用了read方法之后这个流就空了

## 2、FileInputStream#read源码

首先`public int read(byte b[], int off, int len) throws IOException`这个方法最后调用了`private native int readBytes(byte b[], int off, int len)`这个native方法，基础知识告诉我们jdk的native方法实现在同名的.c文件之中，于是我们找到jdk的`FileInputStream.c`的`readBytes`方法：

```c
JNIEXPORT jint JNICALL
Java_java_io_FileInputStream_readBytes(JNIEnv *env, jobject this,
        jbyteArray bytes, jint off, jint len) {
    return readBytes(env, this, bytes, off, len, fis_fd);
}
```

发现实现是在io_util.c中：

```c
jint
readBytes(JNIEnv *env, jobject this, jbyteArray bytes,
          jint off, jint len, jfieldID fid)
{
    jint nread;
  	//栈上分配的8192大小
    char stackBuf[BUF_SIZE];
  	//这里就是八股文上常说的缓冲区
    char *buf = NULL;
  	//文件描述符
    FD fd;

  	//如果要接收的byte数组为空，就NPE
    if (IS_NULL(bytes)) {
        JNU_ThrowNullPointerException(env, NULL);
        return -1;
    }

  	//校验是否越界
    if (outOfBounds(env, off, len, bytes)) {
        JNU_ThrowByName(env, "java/lang/IndexOutOfBoundsException", NULL);
        return -1;
    }

    if (len == 0) {
        return 0;
    } else if (len > BUF_SIZE) {
      	//如果要读取的大小超过默认分配在栈上的8192的话，那么就在内存空间中申请对应的大小
        buf = malloc(len);
      	//申请失败OOM
        if (buf == NULL) {
            JNU_ThrowOutOfMemoryError(env, NULL);
            return 0;
        }
    } else {
      //如果大小不超过8192那么就直接使用栈上分配的，不另外申请
        buf = stackBuf;
    }

  	//获取文件描述符
    fd = GET_FD(this, fid);
  	//如果为-1说明不能读写此文件
    if (fd == -1) {
        JNU_ThrowIOException(env, "Stream Closed");
        nread = -1;
    } else {
      	//调用读取文件到缓冲区
        nread = IO_Read(fd, buf, len);
        if (nread > 0) {
          //用JNI的方法把缓冲区的数据通过memcpy, 进行内存拷贝到Java中也就是byte数组中
            (*env)->SetByteArrayRegion(env, bytes, off, nread, (jbyte *)buf);
        } else if (nread == -1) {
            JNU_ThrowIOExceptionWithLastError(env, "Read error");
        } else { /* EOF */
            nread = -1;
        }
    }

    if (buf != stackBuf) {
      //释放刚刚申请了的内存
        free(buf);
    }
    return nread;
}
```

其实这里看了源码还发现一个彩蛋，就是调用read方法的时候，传入的len最好不要大于8192，因为大于的话就会在堆中申请内存并且需要手动释放，性能很低并且还会增大内存使用非常不划算，小于8192的话缓冲区直接用的就是分配在栈上的了。

这里核心读取文件的代码`IO_Read`是在`io_util_md.h`中定义的，这里`io_util_md`是根据不同的操作系统有一套不同的实现，这里实现就基于两个操作系统的：`windows`和`unix`，由于我电脑是mac服务器也是linux所以直接看unix下的`io_util_md.c`:

```c
ssize_t
handleRead(FD fd, void *buf, jint len)
{
    ssize_t result;
    RESTARTABLE(read(fd, buf, len), result);
    return result;
}
```

这里读取文件是调用了read方法，read方法是在`unistd.h`中定义的，这里大概解释一下`unistd.h`是unix std的意思，是POSIX标准定义的unix类系统定义符号常量的头文件，包含了许多UNIX系统服务的函数原型,是linux为用户提供的统一API接口，方便调用系统提供的一些服务。也就是说这里读取文件用到的是linux底层的io操作的read方法，我们查阅资料可以得知：

> 在`read`方法中，是会自动移动偏移量的。如果你第一次读取了1024个字节，那么下一次读取时，偏移量会从1024开始，读取的内容就是1024到2048的内容。如果你想从头开始读取，可以使用unistd.h的`lseek`方法将偏移量设置为0。

也就是说，我们用操作系统自带的read函数读取文件的时候，实际上它是由一个内置的偏移量的，每次读取都会移动偏移量，到最后读取完成之后偏移量是会指向到文件末尾，所以我们读取文件之后再读取就读取不到内容了，因为偏移量指向了文件末尾，另外jdk提供了一个可重置的流`PushbackInputStream`应该就是调用了unistd.h的`lseek`来重置了偏移量。

所以这里可以回答我们上文中的问题——为什么调用了`FileInputStream`的`read`方法读取完成之后，再读取就读取不到内容了：**因为`FileInputStream`的`read`方法底层调用了操作系统的read方法，每次读取之后都会更新偏移量，所以读取完文件之后偏移量就指向了文件末尾，所以我们再拿同样的一个流再去读取就读取不到任何内容了。**



## 3、一定要调用close吗？

到最后，实际上这个bug就是因为我去改了代码之后才出现的，我这样改的原因就是原来的代码没有调用close，按照八股的说法未关闭的文件流会引起内存泄露，那么我们真的需要调用close吗？不调用会内存泄露吗？

首先什么是内存泄露？定义：当生命周期长的实例`L` **不合理**地持有一个生命周期短的实例`S`，导致`S`实例无法被正常回收

也就是说一个短生命周期的对象被一个长生命周期对象持有了导致短生命周期对象不能被及时回收。

那么有根据我们的八股可以得知，如果一个类没有被GCRoot的跟节点持有的话是可以被回收的，如果我们正常使用FileInputStream的话接口执行完毕就不会有根节点持有他了，那么自然而然是可以被回收的，也就是说正常的使用流，不会导致内存泄露的产生。

那么我们究竟是为什么要close呢？

首先我们看一张图

![](https://ask.qcloudimg.com/http-save/6869253/1qdk9tsel9.jpeg?imageView2/2/w/2560/h/7000)

如上图从左至右有三张表

- file descriptor table 归属于单个进程
- global file table(又称open file table) 归属于系统全局
- inode table 归属于系统全局

#### 从一次文件打开说起

当我们尝试打开文件`/path/myfile.txt`

1.从inode table 中查找到对应的文件节点  2.根据用户代码的一些参数（比如读写权限等）在open file table 中创建open file 节点  3.将上一步的open file节点信息保存，在file descriptor table中创建 file descriptor  4.返回上一步的file descriptor的索引位置，供应用读写等使用。

#### file descriptor 和流有什么关系

- 这个file descriptor就是我们刚刚看到的C源码里的fd，在`FileInputStream`中也有一个属性`private final FileDescriptor fd;`FileInputStream`构造方法会调用`open0()`这个native方法，在我们`FileInputStream.C#open0`中就会调用`fd = handleOpen(ps, *flags*, 0666);`来生成一个fd（0666是 读写权限），简单来说打开文件一次就会生成一个fd。

- 出于稳定系统性能和避免因为过多打开文件导致CPU和RAM占用居高的考虑，每个进程都会有可用的file descriptor 限制。

- 因为文件描述符（file descriptor）属于有限资源，所以如果不释放file descriptor，会导致应用后续依赖file descriptor的行为(socket连接，读写文件等)无法进行，甚至是导致进程崩溃。

- 程序执行很短时间不会造成问题，如果程序运行时间比较长，需要持续打开新的文件，如果不用的文件描述符没有释放，最终文件描述符会消耗殆尽，导致无法打开新的文件

- 当我们调用`FileInputStream.close`后，会释放掉这个file descriptor。

  

因此到这里我们可以说：**不关闭流不是内存泄露问题，是资源泄露问题(file descriptor 属于资源)，也就是说不调用close不会释放文件描述符fd，可能会造成文件描述符会消耗殆尽，导致无法打开新的文件。**

当然了jdk肯定考虑了这点的，所以`FileInputStream`重写了`finalize`方法，并在其中调用了close方法，`finalize`方法会在垃圾回收器回收对象之前被调用

但是需要注意的是，finalize()方法并不是Java中的析构函数，因为Java中没有析构函数的概念，所以调用时间由JVM确定，一个对象的生命周期中只会调用一次拉长了对象生命周期，拖慢GC速度，增加了OOM风险。finalize()方法只是在对象被销毁之前执行一些清理工作的机会。同时，由于finalize()方法的执行时间是不确定的，因此不应该在该方法中执行耗时的操作。并且调用`finalize`在某些垃圾回收器（例如CMS）中是在stw中的，也就是说如果都期望在finalize中close的话我们stw的时间会大大增长，所以我们不能期望在finalize中close掉，我们必须手动给他close掉。

关于finalize网上有一篇博客分析了这个方法的缺点以及线上问题，由于本文着重点在FileInputStream所以就不再赘述，感兴趣的同学可以自行参阅：https://sq.sf.163.com/blog/article/198141339137806336

## 4、最终解决方案

其实最终解决方法就相当相当简单了：

```java
public ResponseInfoVo<String> upload(@RequestParam("file") MultipartFile file) {
  ...
  			try(InputStream inputStream = file.getInputStream(); InputStream md5InputStream = file.getInputStream()){
            String fileMd5 = FileUtils.getFileMD5String(md5InputStream);
            hdfsClient.uploadLocalFile2HDFS(inputStream, hdfsFile);
        }        
    ...
    }
```

开启两个流来读文件就行了。
