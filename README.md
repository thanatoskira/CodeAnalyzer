# CodeAnalyzer

## VsCode 快捷键

* JSON 格式化插件：Pretty Formatter
* 格式化快捷键：Opt + Shift + F
* 取消单行宽度限制：Opt + Z

## Timeline

* Setup: 20230727

## 已知问题

* 不同的搜索方法会存在同一搜索结果(A)的情况，之后对 A 回溯的步骤将存在重复执行的问题，如同一方法 A 中同时调用了如下两个方法，则对如下两个方法分别进行回溯时均会搜索至 A 方法，导致之后对 A 的回溯搜索存在重复工作问题
  * `com.alibaba.fastjson.JSON#toJSONString#(Ljava/lang/Object;)Ljava/lang/String;#9`
  * `com.alibaba.fastjson.JSON#parseObject#null#1`