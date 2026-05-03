package com.onbonbx.serialdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.awt.Font;

import j2a.awt.AwtEnv;
import onbon.bx05.Bx5GEnv;
import onbon.bx05.Bx5GScreenRS;
import onbon.bx06.Bx6GEnv;
import onbon.bx06.Bx6GScreen;
import onbon.bx06.Bx6GScreenProfile;
import onbon.bx06.Bx6GScreenRS;
import onbon.bx06.area.DynamicBxArea;
import onbon.bx06.area.page.TextBxPage;
import onbon.bx06.cmd.dyn.DynamicBxAreaRule;
import onbon.bx06.message.global.ACK;
import onbon.bx06.message.led.ReturnPingStatus;
import onbon.bx06.series.Bx6E;
import onbon.bx06.utils.DisplayStyleFactory;
import onbon.bx06.utils.TextBinary;

public class MainActivity extends AppCompatActivity {



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {

            // @TODO: 注意，这部分初始化代码在整个工程中只能初始化一次，建议放到 application 的 onCreate 函数中

            // init java.awt
            AwtEnv.link(this.getApplication());

            // enable AntiAlias or not
            // 是否启用抗锯齿
            AwtEnv.configPaintAntiAliasFlag(false);

            // 初始化五代SDK
            // init bx05 sdk
            //Bx5GEnv.initial();

            // 初始化六代SDK
            // init bx06 sdk
            Bx6GEnv.initial();

        }
        catch (Exception ex) {
        }


        new Thread(new Runnable() {
            @Override
            public void run() {

                // Create screen object, we create a bx05 screen using serial port
                // 创建 screen 对象，这里创建的是一个五代控制器的串口控制器对象
                Bx6GScreenRS screen = new Bx6GScreenRS("Screen1", new Bx6E());
                //
                // connect to the screen
                // 连接控制器
                screen.connect("/dev/ttyS4", Bx6GScreenRS.BaudRate.RATE_57600);
                Bx6GScreenProfile profile = screen.getProfile();

                for(int i=0; i<1024; i++) {

                    System.out.println("try " + i );



                    //
                    // send command to screen
                    // 发送命令到控制器
                    /*
                    Bx6GScreen.Result<ReturnPingStatus> status = screen.ping();
                    if(status.isOK()) {
                        System.out.println("It's ok");
                    }
                    else {
                        System.out.println("error");
                        continue;
                    }
                    */


                    DisplayStyleFactory.DisplayStyle[] styles = DisplayStyleFactory.getStyles().toArray(new DisplayStyleFactory.DisplayStyle[0]);

                    // 创建动态区
                    // BX-6E BX-6EX系列支持4个动态区，BX-6Q系列支持32个动态区
                    DynamicBxAreaRule rule = new DynamicBxAreaRule();
                    // 设定动态区ID ，此处ID为0 ，多个动态区ID不能相同
                    rule.setId(0);
                    // 设定异步节目停止播放，仅播放动态区
                    // 0:与异步节目一起播放
                    // 1:异步节目 停止播放，仅播放动态区
                    // 2:当播放完节目编号坐高的异步节目后播放该动态区
                    rule.setImmediatePlay( (byte)1 );
                    // 设定动态区循环播放
                    // 0:循环显示
                    // 1:显示完成后静止显示最后一页数据
                    // 2:循环显示，超过设定时间后数据仍未更新时不再显示
                    // 3:循环显示，超过设定时间后数据仍未更新时显示Logo信息
                    // 4:循环显示，显示完成最后一页后就不再显示
                    rule.setRunMode( (byte)0 );

                    DynamicBxArea area = new DynamicBxArea( 0,0,128,24, profile);

                    TextBxPage page = new TextBxPage("hello, world");

                    page.setFont( new Font( "宋体",Font.PLAIN,20 ) );

                    page.setDisplayStyle( styles[2] );
                    page.setVerticalAlignment(TextBinary.Alignment.FAR);
                    page.setHorizontalAlignment(TextBinary.Alignment.CENTER);

                    area.addPage( page );
                    Bx6GScreen.Result<ACK> result = screen.writeDynamic( rule,area );
                    if(result.isOK()) {
                        System.out.println("try " + i + " is ok");
                    }
                    else {
                        System.out.println("try " + i + " is bad");
                    }



                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }


                }

                // disconnect
                // 断开连接
                screen.disconnect();
            }
        }).start();




    }
}