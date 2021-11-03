# 用Python来做微信聊天记录的词云

## 前言

1024到啦，想用自己的一技之长给对象准备点什么吗？

平时使用频率最高的APP是什么？没错就是微信，微信记录了你们平常生活的点点滴滴，所以我们可以用微信聊天记录来生成词云，接下来笔者将会告诉大家如何使用Python来生成微信聊天记录的词云。

效果图：![qiaoqiao](/Users/zhangyunfan/Desktop/qiaoqiao.png)



写在前面：首先本教程只能针对iphone手机，因为安卓手机每个品牌导出微信聊天记录的方法都不一样，甚至有的安卓手机需要root，所以这里就不用安卓手机了，如果有读者对安卓手机感兴趣可以参考：https://godweiyang.com/2019/08/09/wechat-explore/

## 准备工作



首先需要准备一部有你们聊天记录对iphone手机和一台电脑。

电脑需要有python环境，如果是Windows需要手动安装Python环境：https://zhuanlan.zhihu.com/p/111168324?from_voters_page=true；如果是Mac电脑那MacOs都是自带Python环境的，如果没有去修改Python的路径那需要使用命令`python3`、`pip3`才能使用下面的python代码。

如果电脑是Windows还需要安装iTunes，因为我们要备份iphone上的数据。



## 导出ihone上的微信聊天记录



1、用iTunes连接iPhone，将内容备份到电脑上。请注意，不要选择”给iPhone备份加密“！

![](http://wxbackup.imxfd.com/images/itunes-only.png)

2、下载微信聊天记录导出工具`wxbackup`：http://wxbackup.imxfd.com/

选择MacOs或者windows就好了。

3、打开刚刚下载的wxbackup，windows下应该会自己找到刚刚备份的iphone的备份文件，如果不行就自己选择文件夹，在itunes中打开备份文件所在未知，Mac下有点麻烦，因为itunes的备份目录所在的目录`/Library`是隐藏目录所以在工具中无法找到，这里有两个方法了：1、把备份文件的文件夹`MobileSync`拷贝到桌面或者其他可以选择目录；2、按下`command+shift+.`显示隐藏目录；

4、选择你的账号和你要导出聊天记录的联系人，可以是联系人也可以是微信群，瞬间即可导出选中的聊天记录。支持增量导出，即有新的内容更新到iPhone备份文件后，可以增加更新的内容到导出记录中。

![](http://wxbackup.imxfd.com/images/wxbackup-only.png)





## 生成词云

1、找到刚刚导出的聊天记录，找到js目录下的`message.js`，然后打开，去掉文件开头的`var data =`然后另存为`json`文件将文件名改为`message.json`就可以了。（其实这步可以省略的但是笔者摸鱼时间有限就没有加上把js文件转换成json文件的代码了，最近挺忙的（忙还有时间摸鱼其实就是懒））

2、下载笔者的词云工具：https://pan.baidu.com/s/1-P_VYJCkPm8fOIwaRa3KfA 提取码：c5td（最近笔者上传代码到github一直失败，加上有读者可能打不开github就没放到github上）

3、解压压缩包然后进入到`wxClound`中，打开命令行（mac在文件夹上一层右键选中然后点击’服务‘然后选择’新建位于文件夹位置的终端窗口‘，windows电脑在文件夹的地址栏直接输入'cmd'或者右键菜单中打开命令行）

4、windows下执行命令`pip install -r requirements.txt`，mac下执行`pip3 install -r requirements.txt` 来安装依赖，如果提示`no command pip`的话，说明你的python安装没有完成，请跳回到上文中的安装Python环境中仔细检查。

5、执行命令：` python wxclound.py 你刚刚另存为json的文件的全路径 你期望生成图片的所在路径`，比如说:` python wxclound.py /Users/zhangyunfan/Desktop/message /Users/zhangyunfan/Desktop` 然后就会在你指定的路径上生成一个`result.png`了，这个就是根据你聊天记录所生成的词云。

6、当然了并不是所有人审美都和笔者一样，所以笔者提供了一个不要特殊字体和背景的简单词云生成文件：`simplewxclound.py`，将5中命令中的`wxclound.py`替换成`simplewxclound.py`就可以了，生成的就是`simpleresult.png`



## 后记

其实笔者做这个的初心只是想统计一下微信群中的发言次数，然后上网搜有没有现成的工具的时候发现目前并没有一键统计的工具，刚好笔者最近在学习Python，于是就打算自己干，然后网上搜资料的时候不小心发现了聊天记录还可以用来做词云，于是做出了这个。过程还是比较曲折的，网上资料也不是特别完整的，而且笔者的Python还是挺菜的，属于是边写边百度的水平：

![WX20211023-172313](/Users/zhangyunfan/Desktop/WX20211023-172313.png)

不过结果还算成功，笔者不仅统计了微信群里发言活跃数（摸鱼程度），还成功生成了词云，也许这个就是学习所带来的成就感吧。

另外附统计群里发言次数的代码：

```python
import json

with open("json文件所在绝对路径", 'r') as content_j:
    load_dict = json.load(content_j)
member_dict = {}
for key in load_dict["member"].keys():
    member_dict[key] = load_dict["member"][key]['name']
d = {}
for message in load_dict['message']:
    key = ''
    if message['m_nsRealChatUsr'] in member_dict:
        key = member_dict[message['m_nsRealChatUsr']]
        if member_dict[message['m_nsRealChatUsr']] == '':
            key = '自己的账号'
    else:
        key = '未知用户'
    if key in d:
        d[key] = d[key] + 1
    else:
        d[key] = 1

a = sorted(d.items(), key=lambda kv: (kv[1], kv[0]))
print(a)
for key in a:
    print('用户名是'+key[0]+'发言数是'+str(key[1]))

```

