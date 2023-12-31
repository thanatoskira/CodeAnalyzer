# CodeAnalyzer

## VsCode 快捷键

* JSON 格式化插件：Pretty Formatter
* 格式化快捷键：Opt + Shift + F
* 取消单行宽度限制：Opt + Z

## Timeline

* Setup: 20230727
* 20231101: 新增 3 个系统属性
    * log.print: 默认 false，日志打印
    * params.empty.scan: 默认 false，开启空参数函数回溯
    * jdk.scan: 默认 false，开启 jdk 回溯扫描

## 已知问题

* 不同的搜索方法会存在同一搜索结果(A)的情况，之后对 A 回溯的步骤将存在重复执行的问题，如同一方法 A
  中同时调用了如下两个方法，则对如下两个方法分别进行回溯时均会搜索至 A 方法，导致之后对 A 的回溯搜索存在重复工作问题
    * `com.alibaba.fastjson.JSON#toJSONString#(Ljava/lang/Object;)Ljava/lang/String;#9`
    * `com.alibaba.fastjson.JSON#parseObject#null#1`
* [x] 如果方法调用位于如 `() -> {invoke();}` 的 lambda 中会导致回溯中断
* [ ] 暂时是是对每个 call 单独进行回溯，是否可以同时扫描多个 call 减少执行次数
* [x] 存在如 spring-beans-5.3.20.jar 文件，缺少 pom.xml 文件，但可以通过 MANIFEST.MF 正常获取到 pkgName，同时也不位于 unCertainFiles 列表中