using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Runtime.InteropServices;
using System.IO;
using System.Threading;

namespace LedSDKDemo_CSharp
{
    class Program
    {
        //控制卡IP
        public static byte[] ip = Encoding.GetEncoding("GBK").GetBytes("192.168.3.199");
        //控制卡端口
        public static ushort port = 5005;
        //串口号 "COM1",大于9以上做特殊处理，如"\\\\.\\COM17"
        public static byte[] com = Encoding.GetEncoding("GBK").GetBytes("COM3");
        //串口波特率 1：9600  2：57600
        public static byte baudRate = 2;
        //通讯方式  true=网口  false=串口
        public static Boolean check = true;

        public delegate int MethodCaller(int name);//定义个代理 
        static void Main(string[] args)
        {
            //初始化动态库
            int err = bxdualsdk.bxDual_InitSdk();
            int a= 0;
            //bxdualsdk.Ping_data data = new bxdualsdk.Ping_data();
        //    err = bxdualsdk.bxDual_cmd_uart_searchController(ref data, Program.com);
        
        //Console.WriteLine("ControllerType:0x" + data.ControllerType.ToString("X2"));
        //    Console.WriteLine("FirmwareVersion:V" + System.Text.Encoding.Default.GetString(data.FirmwareVersion));
        //    Console.WriteLine("ipAdder:" + System.Text.Encoding.Default.GetString(data.ipAdder));
        //    Console.WriteLine("\r\n");
            //common_56.Net_Bright(2);
            //bxdualsdk.Ping_data data = new bxdualsdk.Ping_data();
            //err = bxdualsdk.bxDual_cmd_tcpPing(Program.ip, Program.port, ref data);

            //Console.WriteLine("ControllerType:0x" + data.ControllerType.ToString("X2"));
            //Console.WriteLine("FirmwareVersion:V" + System.Text.Encoding.Default.GetString(data.FirmwareVersion));
            //Console.WriteLine("ipAdder:" + System.Text.Encoding.Default.GetString(data.ipAdder));
            //Console.WriteLine("ScreenWidth:" + data.ScreenWidth.ToString());
            //Console.WriteLine("ScreenHeight:" + data.ScreenHeight.ToString());
            //Console.WriteLine("cmb_ping_Color:" + data.Color.ToString());
            //Console.WriteLine("\r\n");
            //common_56.sendConfigFile();
            //Console.Write("请输入串口：");
            //com = Encoding.GetEncoding("GBK").GetBytes(Console.ReadLine());
            // err = bxdualsdk.bxDual_cmd_check_time(ip, port);
            //if (err == 0) { Console.WriteLine("校时成功"); } else { Console.WriteLine("校时失败"); }
            //BX-5代控制卡
            if (!check)
            {
                //Program_Send_Sensor 节目设置传感器区域调用示例代码
                //Program_Send_Sensor.Send_program_sensor_5();
                //Program_Send_clock文本调用示例代码
                Program_Send_txt.Send_program_txt_5();

                //Program_Send_png图片调用示例代码
               // Dynamic_5.delete_dynamic();
                //Program_Send_png.Send_program_png_5();

                //Program_Send_time时间调用示例代码
                //Program_Send_time.Send_program_time_5();

                //Program_Send_clock表盘调用示例代码
                //Program_Send_clock.Send_program_clock_5();

                //Program_Send_Areas节目多个区域调用示例代码
                //Program_Send_Areas.Send_program_areas_5();

                //Send_program_sensor_5 节目设置传感器区域调用示例代码
                //Program_Send_Sensor.Send_program_sensor_5();

                //动态区调用示例，仅限BX-5E系列使用
                //Dynamic_5.updata_dynamic_pages();
                //Dynamic_5.updata_dynamic_txt();
                //删除动态区
                //Dynamic_5.delete_dynamic();
                //Random ra = new Random();
                //for(int i = 0; i < 10000; i++)
                //{
                //    string str = "ab" + ra.Next(1,4999);
                //    Dynamic_5.updata_tests(0,64,0,44,16, str);
                //     str = "是d" + ra.Next(4999,9999);
                //    Dynamic_5.updata_tests(1,64, 16, 64, 16, str);
                //     str = "gf" + ra.Next(1, 99);
                //    Dynamic_5.updata_tests(2, 108, 0, 20, 16, str);
                //    Thread.Sleep(2000);
                //}
            }
            //BX-6代控制卡
            if (check)
            {
                //Program_Send_Sensor 节目设置传感器区域调用示例代码
                //Program_Send_Sensor.Send_program_sensor_6();
                //Program_Send_txt文本调用示例代码
                //Program_Send_txt.Send_program_txt_6();
                //common_56.deleteprogram();

                //Program_Send_png图片调用示例代码
                //Program_Send_png.Send_program_png_6();

                //Program_Send_time时间调用示例代码
                //Program_Send_time.Send_program_time_6();

                //Program_Send_clock表盘调用示例代码
                //Program_Send_clock.Send_program_clock_6();

                //Program_Send_Areas节目多个区域调用示例代码
                //Program_Send_Areas.Send_program_areas_6(); 


                //动态区调用示例，部分控制卡支持
                //Dynamic_6.dynamicArea_pages_1();
                Dynamic_6.dynamicArea_str_3();
                //Dynamic_6.dynamicArea_png_1();

                //删除动态区
                //Dynamic_6.delete_dynamic();
            }

            //服务器模式调用示例
            if (!check)
            {
                Server.Server_get();
            }

            //释放动态库
            //bxdualsdk.bxDual_ReleaseSdk();
            Console.ReadKey();
        }
        
    }
}
