
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace LedSDKDemo_CSharp
{
    class send
    {
        public static void test()
        {
            Thread thread1 = new Thread(t => Program_Send_txt.Send_program_txt_6());
            thread1.Start();
            Thread thread2 = new Thread(t => dynamicArea_str_5());
            thread2.Start();

        }
        /// <summary>
        /// 同时更新多个动态区文本
        /// </summary>
        public static void dynamicArea_str_5()
        {
            for (int i = 0; i < 999; i++)
            {
                Console.WriteLine("++++++++++++++++++++:" + i);
                Thread.Sleep(1000);
            }
        }
    }
}