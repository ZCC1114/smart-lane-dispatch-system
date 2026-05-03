# bx.dual.sdk.android
This is the android version sdk for bx05/06 led controllers from onbon(www.onbonbx.com).

## Import to android studio
1. copy sdk libs to directory 'libs'

![avatar](https://github.com/onbonlab/bx.dual.sdk.android/blob/master/doc/pic/copy%20libs.png)


2. implementation  in '.gradle' file like below
```gradle
implementation files('libs/log4j-1.2.14.jar')
implementation files('libs/simple-xml-2.7.1.jar')

implementation files('libs/uia-utils-0.2.0.jar')
implementation files('libs/uia-message-0.6.0.jar')
implementation (name: 'uia-comm-android-release', ext: 'aar')

implementation files('libs/bx05-0.5.0-SNAPSHOT.jar')
implementation files('libs/bx05.message-0.5.0-SNAPSHOT.jar')
implementation files('libs/bx06-0.6.0-SNAPSHOT.jar')
implementation files('libs/bx06.message-0.6.0-SNAPSHOT.jar')

implementation (name:'serialport-1.0.1', ext:'aar')
implementation (name: 'java.awt4a-0.1-release', ext: 'aar')

```

## Use the sdk
1. Init the sdk in applicaion 
The sdk must be initialized before it be used. It should be like this.
```java
try {
      // init java.awt
      AwtEnv.link(this);   
      
      // enable AntiAlias or not
      // 是否启用抗锯齿
      AwtEnv.configPaintAntiAliasFlag(false);     

      // 初始化五代SDK
      // init bx05 sdk
      Bx5GEnv.initial();
  
      // 初始化六代SDK
      // init bx06 sdk
      Bx6GEnv.initial();
  }
  catch (Exception ex) {
  }

```

2. Use it like below
```java

// Create screen object, we create a bx05 screen using serial port
// 创建 screen 对象，这里创建的是一个五代控制器的串口控制器对象
Bx5GScreenRS screen = new Bx5GScreenRS("Screen1");

//
// connect to the screen
// 连接控制器
screen.connect("/dev/ttyS4", Bx5GScreenRS.BaudRate.RATE_57600);

// 
// send command to screen
// 发送命令到控制器
screen.ping();

......

// disconnect
// 断开连接
screen.disconnect();
```


