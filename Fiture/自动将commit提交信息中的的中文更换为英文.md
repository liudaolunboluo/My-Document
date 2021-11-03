# 自动将commit提交信息中的的中文更换为英文

1、进入源码工程的根目录

2、打开终端

3、执行`cd .git`

4、然后再进入`cd hooks`，若没有这个目录则`mkdir hooks`新建之

5、将文件`commit-msg`拷贝到这个目录下，此时的目录结构应该是：

```shell
├── .git
│   ├── hooks
│   │   ├── commit-msg
```

6、在hooks目录下执行命令：`chmod +x commit-msg`

7、安装jq，`brew install jq`

备注：若电脑上没有安装brew则可以通过如下命令安装：`/bin/zsh -c "$(curl -fsSL https://gitee.com/cunkai/HomebrewCN/raw/master/Homebrew.sh)"`