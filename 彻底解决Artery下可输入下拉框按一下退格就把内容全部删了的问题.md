# 彻底解决Artery下可输入下拉框按一下退格就把内容全部删了的问题

众所周知Artery的可输入的下拉框只要按一下backspace就会把输入的内容全部删除，很多人都发现了也有过[解决方案1](http://artery.thunisoft.com/posts/detail/4bce0ae889fb44ebba4403f5d5885baa) [解决方案2](http://artery.thunisoft.com/posts/detail/4b8868252a564aa987ff193f3bf5074b) 但是都是自己新作一个控件来绕过Artery的控件，本人本着研究的精神终于在Artery的源码中找到了这个删除事件的幕后黑手。

好我们就不废话了直接上代码，在Artery的`artery-engine-x.x.x.jar` （验证过后4和5是一样的路径）包里的`/web/artery/arteryPlugin/form/pub/artery-select.js`  里面的

```javascript
//块操作
	blockOper:function(outRef){
		outRef.el.keydown($.proxy(function(e){
			if(Artery.get(outRef.id).isDisabled()){
				return;
			}
			var obj = Artery.getTopWindow().document.getElementById(outRef.id);
			if (!obj)
				obj = document.getElementById(outRef.id);
			obj.focus();//光标位置不变 
			var start = null;
			var end = null;
			var isLast = false;
			if(obj.selectionStart!=undefined){
				start = obj.selectionStart;
				end = obj.selectionEnd;
			}else{
				var r = Artery.getTopWindow().document.selection.createRange(); 
				r.collapse(false); 
				r.setEndPoint("StartToStart", obj.createTextRange()); 
				start = r.text.length;
				end = start;
			}
			isLast = start==end && obj.value.toString().length==start;
			var ovt = obj.value.toString(); 
			// 先保证结尾无分号使split后获得元素正确
			if (ovt.length>0 && ovt[ovt.length-1]==";") {
				ovt = ovt.substring(0,ovt.length-1);
			}
			if (isLast) {
				start = ovt.length;
				end = start;
			}
			
			var ovts = ovt.split(';');
			if(outRef.hiddenEl != undefined){
				var ovs = outRef.hiddenEl.val().toString().split(';');
			}
			var fromIndex = ovt.substring(0,start).split(";").length-1;
			var endIndex = ovt.substring(0,end).split(";").length;
	
			if(e.keyCode==8 
				|| e.keyCode==46){ //backspace,delete
				ovts.splice(fromIndex,endIndex-fromIndex);
				obj.value = ovts.join(';');
				if(outRef.hiddenEl != undefined){
					ovs.splice(fromIndex,endIndex-fromIndex);
					var ovsStr = ovs.join(';');
					outRef.hiddenEl.val(ovsStr);
				}
				// 如果text最后一个元素不是手动输入的(通过比对ovts和ovs的个数)，就在结尾加上分号
				if (ovts.length > 0 && ovts.length == ovs.length)
					obj.value += ';';
				return false;
			}
			if(!isLast 
				&& e.keyCode!=9 //tab
				&& e.keyCode!=13 //enter 
				&& e.keyCode!=35 //end
				&& e.keyCode!=36 //home
				&& e.keyCode!=37 //left
				&& e.keyCode!=39 //right
				){
				return false;
			}
		},outRef));	
	},
```

这个方法里，核心就是

```javascript
if(e.keyCode==8 
				|| e.keyCode==46){ //backspace,delete
				ovts.splice(fromIndex,endIndex-fromIndex);
				obj.value = ovts.join(';');
				if(outRef.hiddenEl != undefined){
					ovs.splice(fromIndex,endIndex-fromIndex);
					var ovsStr = ovs.join(';');
					outRef.hiddenEl.val(ovsStr);
				}
				// 如果text最后一个元素不是手动输入的(通过比对ovts和ovs的个数)，就在结尾加上分号
				if (ovts.length > 0 && ovts.length == ovs.length)
					obj.value += ';';
				return false;
			}
```

这里，Artery监听了backspace和Delet按键的敲击事件，我们可以清楚的看到Artery是把下拉框的内容用了分号隔开当成数组处理按下删除实际上是想删除分号隔开的内容的最后一个内容，比如`a;b;ab;ac` 这样是想删除ac，为什么这样设计呢，因为下拉框的内容一个就认为是一起的，比如说我下拉框是"abc ab ac"这样，那么"abc"是一个内容"ab"是一个内容，我在删除下拉框的时候删除"abc"是合理的，因为你肯定是按照选项单位来删除的，实际上这个是合理的，但是没有考虑到下面两种情况：

1、下拉框是有搜索功能的，我输入'abc'输入多了然后我想删除c只留下ab来搜索"ababa"这个选项，但是我一删发现abc都删除了，这个场景我在使用研道搜索人员的时候经常遇到

2、下拉框带输入功能，下拉框的内容满足不了我，我要自己输入结果一个字输错了我按下删除全部给我删了，这个场景在减刑假释里面有。



所以我们可以来改造了，很简单三行代码搞定，

```javascript
	if(e.keyCode==8 
				|| e.keyCode==46){ //backspace,delete
				if(this.editable){
					return;
				}					
				ovts.splice(fromIndex,endIndex-fromIndex);
```

直接在刚刚哪里捕获到推格和删除事件时，如果是可编辑的主要是针对1和2的情况，那么我就不监听了直接return。改完之后直接丢到jar包里，就可以了，值得注意的是Myeclipse下必须要删除源码目录下的lib目录里的jar包因为Myeclipse优先取的是源码里的lib目录里的jar包，你只改了你本地maven仓库的jar包是不行的。



**如果是情况2，那么这样改造之后直接取是取不到值的，必须要在提交名称后面加Text才能取到你输入的值，比如你提交名称是CAh，那么如果你是在输入库纯手输的话那么应该去CAhText这个才能拿到你输入的内容**



当然了这样修改也许欠妥但是可以满足我的要求了，各位可以根据自己的需求来修改

