using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Runtime.InteropServices;
using System.Threading;

namespace LedSDKDemo_CSharp
{
    class Server
    {
            public static List<ServerList> server_list = new List<ServerList>();
        public static void Server_get()
        {
            int err = 0;
            int ServerPort = 8134;
            int port = 5005;
            //启动服务器
            int pServer = bxduaisdkServer.bxDual_Start_Server(ServerPort);
            byte[] cards = new byte[2048];
            //控制卡上线个数
            int count = 0;
            Thread.Sleep(2000);
            count = 0;
            server_list.Clear();

            for (int i = 0; i < 2048; i++) { cards[i] = 0; }
            while (count == 0)
            {
                //获取控制卡数据与上线数量
                count = bxduaisdkServer.bxDual_Get_CardList(cards);
                Thread.Sleep(1000);
            }
            server_list.Clear();
            //一个控制卡数据20个长度
            for (int i = 0; i < count; i++)
            {
                //前16位数据是控制卡网络ID编号
                byte[] barcodevalue = cards.Skip(0 + i * 20).Take(16).ToArray();
                //根据网络ID获取通讯使用端口
                port = bxduaisdkServer.bxDual_Get_Port_Barcode(barcodevalue);
                ServerList price = new ServerList(barcodevalue, port);
                server_list.Add(price);
                string ssss = Encoding.Default.GetString(barcodevalue);
                Console.WriteLine("barcode:" + i + "：" + System.Text.Encoding.Default.GetString(barcodevalue) + "   port:" + port);
                //server_list.Add(price);
            }
            //启动线程，判断控制卡在线情况
            Thread thread = new Thread(t => get());
            thread.Start();
            bool pl = false;
            while (pl)
            {
            //以第一张上线控制卡做通信示例
            //服务器IP
            byte[] server_ip = Encoding.GetEncoding("GBK").GetBytes("192.168.89.100");
            Program.ip = server_ip;
                List<ServerList> server_list1 = server_list;
                if (server_list1.Count ==1) { 
                for (int a=0;a< server_list1.Count; a++) {
                    int b = a;
                        int p = server_list1[b].port;
            Thread thread1 = new Thread(t => SendTextMsg(p));
            thread1.Start();
                }
            }
                Thread.Sleep(1000); //pl = false;
            }
            //关闭服务器
            //err = bxduaisdkServer.bxDual_Stop_Server(pServer);
            //结束线程
            //thread.Abort();
            //while (thread.ThreadState != ThreadState.Aborted)
            //{
            //    Thread.Sleep(100);
            //}

            //bxduaisdkServer.bxDual_ReleaseSdk();
        }
        public static void get()
        {
            while (true)
            {
                byte[] cards = new byte[2048];
                //控制卡上线个数
                int count = 0;
                //List<ServerList> server_list = new List<ServerList>();
                count = 0;
                server_list.Clear();
                for (int i = 0; i < 2048; i++) { cards[i] = 0; }
                while (count == 0)
                {
                    Thread.Sleep(2000);
                    //获取控制卡数据与上线数量
                    count = bxduaisdkServer.bxDual_Get_CardList(cards);

                    Console.WriteLine(DateTime.Now.ToString()+"    count：" + count);
                }
                if (server_list.Count != count) { 
                server_list.Clear();
                //一个控制卡数据20个长度
                for (int i = 0; i < count; i++)
                {
                    //前16位数据是控制卡网络ID编号
                    byte[] barcodevalue = cards.Skip(0 + i * 20).Take(16).ToArray();
                    //根据网络ID获取通讯使用端口
                    int port = bxduaisdkServer.bxDual_Get_Port_Barcode(barcodevalue);
                        SendTextMsg(port);
                    ServerList price = new ServerList(barcodevalue, port);
                    server_list.Add(price);
                    Console.WriteLine("barcode:" + i + "：" + System.Text.Encoding.Default.GetString(barcodevalue) + "   port:" + port);
                    Thread.Sleep(2000);
                    //server_list.Add(price);
                }
            }
            }
        }

        /// <summary>
        /// 发送消息
        /// </summary>
        /// <param name="data"></param>
        /// <param name="client"></param>
        private static void SendTextMsg(int port)
        {
            //while (true)
            //{
                try
                {
                    byte[] server_ip = Encoding.GetEncoding("GBK").GetBytes("192.168.89.100");
                bxdualsdk.EQpageHeader_G6 pheader;
                pheader.PageStyle = 0x00;
                pheader.DisplayMode = 4;
                pheader.ClearMode = 0x00;
                pheader.Speed = 15;
                pheader.StayTime = 0;
                pheader.RepeatTime = 1;
                pheader.ValidLen = 0;
                pheader.CartoonFrameRate = 0x00;
                pheader.BackNotValidFlag = 0x00;
                pheader.arrMode = bxdualsdk.E_arrMode.eSINGLELINE;
                pheader.fontSize = 12;
                pheader.color = (uint)0x01;
                pheader.fontBold = 0;
                pheader.fontItalic = 0;
                pheader.tdirection = bxdualsdk.E_txtDirection.pNORMAL;
                pheader.txtSpace = 0;
                pheader.Valign = 1;
                pheader.Halign = 1;
                byte[] str = Encoding.GetEncoding("GBK").GetBytes("1.png\0");
                IntPtr img = Marshal.AllocHGlobal(str.Length);
                Marshal.Copy(str, 0, img, str.Length);
                Console.WriteLine("发送节目完成" + DateTime.Now.ToString());
                //网口
                    //动态区优先播放，节目停止播放
                    int err = bxdualsdk.bxDual_dynamicArea_AddAreaPic_6G(Program.ip, Program.port, bxdualsdk.E_ScreenColor_G56.eSCREEN_COLOR_DOUBLE, 0, 0, 0,
                                                          320, 320, ref pheader, img);
                Console.WriteLine("bxDual_dynamicArea_AddAreaTxtDetails_6G:" + err + "=======" + port);
                Console.WriteLine("发送节目完成" + DateTime.Now.ToString());
            }
                catch (Exception ex)
                {

                }

            }
        //}
    }
}
