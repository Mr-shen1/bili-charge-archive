# 类B站动态评论页

# 背景

爬取B站up主的动态和评论，持久化后，做一个类似B站动态的页面，初步只展示up主列表和评论

## 前端

### up列表、动态列表

![image-20260925163908393](https://oss-picgo-skf.oss-cn-hangzhou.aliyuncs.com/ob/img/202609251639510.png)



![image-20260925164206000](https://oss-picgo-skf.oss-cn-hangzhou.aliyuncs.com/ob/img/202609251642066.png)

### 动态及评论详情

![image-20260925164818336](https://oss-picgo-skf.oss-cn-hangzhou.aliyuncs.com/ob/img/202609251648376.png)

查询只按最新排序，更改为分页查询，每页20条



总体参考b站

### 技术选型



## 后端

### 爬取

> b站动态API：https://github.com/alittlehuaji/bilibili-api-collect-mirror/tree/master/docs/dynamic

> b站评论API：https://github.com/alittlehuaji/bilibili-api-collect-mirror/tree/master/docs/comment

目前使用的是Python脚本将爬取后的内容发送到飞书群中，暂无做持久化

### 表结构

参考页面及上边api，待讨论

### 页面查询



### 技术选型





# 部署

部署到我的阿里云服务器上