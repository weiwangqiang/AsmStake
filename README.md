# Asm插桩demo

其中：

- plugin目录：用于定义插桩的插件，使用asm编写，在 ./plugin/build.gradle中定义publishing 任务，
  用于将插件发布到本地，指定在项目根路径下的repo文件中
- app目录：用于测试插件的demo

## 插件发布

在gradle任务中，点击plugin-》task-》publishing-》publish任务即可发布到本地

## 导入说明

需要在项目根路径下的build.gradle文件中声明

```java
buildscript {
    dependencies {
        // 声明插件的依赖版本
        classpath 'com.asm.plugin:time:1.0.0'
    }
}
```

以及项目根路径下的settings.gradle (针对gradle是7以上的版本，低版本在build.gradle文件中声明) 中声明

```java
pluginManagement {
    repositories {
        maven {
            url uri("./repo") // 声明本地maven地址
        }
    }
}
```

然后在指定muddle下声明插件

```java
plugins {
    id 'auto-add-systrace'
}
```


## 参考文档

- [使用ASM完成编译时插桩](https://zhuanlan.zhihu.com/p/158758613)
- [Git项目-TraceFix](https://github.com/Gracker/TraceFix)

## 关于字节码

可以使用 `Jadx class Decompiler` 插件查看字节码