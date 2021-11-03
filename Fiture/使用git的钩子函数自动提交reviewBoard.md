# 使用git的钩子函数自动提交reviewBoard



1、进入源码工程的根目录

2、打开终端

3、执行`cd .git`

4、然后再进入`cd hooks`，若没有这个目录则`mkdir hooks`新建之

5、将文件`post-commit`拷贝到这个目录下，此时的目录结构应该是：

```shell
├── .git
│   ├── hooks
│   │   ├── post-commit
```

6、在hooks目录下执行命令：`chmod +x post-commit`

7、每次提交的时候若要进行code review则在输入完commit message之后在最后添加`-review`即可在commit之后自动提交到reviewBoard了，若要沿用上一次的review记录则在`-review`后面在添加一个参数`-id 你上一次提交的reviewID`就可以了



