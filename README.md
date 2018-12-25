# checkCustomViewExistUtil

本工具用来检查 Android 布局文件中引用的自定义 View 是否已被声明存在, 如果没有, 在程序运行时会出现 ClassNotFoundException. 使用方法:
java -jar checkViewClass.jar {apk or aar 文件路径}
