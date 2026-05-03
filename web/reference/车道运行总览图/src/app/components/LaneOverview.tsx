import { useState } from 'react';
import { TrafficLight } from './TrafficLight';
import { Car, ArrowRight, ArrowLeft } from 'lucide-react';

interface Lane {
  id: number;
  name: string;
  color: string;
  startLight: 'red' | 'green'; // 左侧灯 - 入口
  endLight: 'red' | 'green'; // 右侧灯 - 出口
  taxiCount: number;
  capacity: number;
}

export function LaneOverview() {
  const [lanes, setLanes] = useState<Lane[]>([
    { id: 1, name: '1号车道', color: '#3b82f6', startLight: 'green', endLight: 'green', taxiCount: 3, capacity: 8 },
    { id: 2, name: '2号车道', color: '#8b5cf6', startLight: 'green', endLight: 'red', taxiCount: 5, capacity: 8 },
    { id: 3, name: '3号车道', color: '#ec4899', startLight: 'red', endLight: 'green', taxiCount: 2, capacity: 8 },
    { id: 4, name: '4号车道', color: '#f59e0b', startLight: 'green', endLight: 'green', taxiCount: 7, capacity: 8 },
    { id: 5, name: '5号车道', color: '#10b981', startLight: 'green', endLight: 'green', taxiCount: 4, capacity: 8 },
    { id: 6, name: '6号车道', color: '#06b6d4', startLight: 'red', endLight: 'red', taxiCount: 8, capacity: 8 },
    { id: 7, name: '7号车道', color: '#6366f1', startLight: 'green', endLight: 'green', taxiCount: 1, capacity: 8 },
    { id: 8, name: '8号车道', color: '#ef4444', startLight: 'green', endLight: 'red', taxiCount: 6, capacity: 8 },
    { id: 9, name: '9号车道', color: '#f97316', startLight: 'green', endLight: 'green', taxiCount: 3, capacity: 8 },
    { id: 10, name: '10号车道', color: '#84cc16', startLight: 'red', endLight: 'green', taxiCount: 5, capacity: 8 },
    { id: 11, name: '11号车道', color: '#14b8a6', startLight: 'green', endLight: 'green', taxiCount: 2, capacity: 8 },
  ]);

  const toggleStartLight = (id: number) => {
    setLanes(lanes.map(lane => 
      lane.id === id 
        ? { ...lane, startLight: lane.startLight === 'red' ? 'green' : 'red' } 
        : lane
    ));
  };

  const toggleEndLight = (id: number) => {
    setLanes(lanes.map(lane => 
      lane.id === id 
        ? { ...lane, endLight: lane.endLight === 'red' ? 'green' : 'red' } 
        : lane
    ));
  };

  const canEnter = (lane: Lane) => {
    // 左侧绿灯且未满载才能进入
    return lane.startLight === 'green' && lane.taxiCount < lane.capacity;
  };

  const canExit = (lane: Lane) => {
    // 右侧绿灯且有车才能出去
    return lane.endLight === 'green' && lane.taxiCount > 0;
  };

  const isFull = (lane: Lane) => {
    return lane.taxiCount >= lane.capacity;
  };

  return (
    <div className="h-screen bg-gradient-to-br from-blue-50 via-white to-purple-50 p-4 overflow-hidden">
      <div className="max-w-7xl mx-auto h-full flex flex-col">
        {/* 标题 */}
        <div className="text-center mb-3">
          <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-600 to-purple-600 bg-clip-text text-transparent mb-1">
            出租车蓄车池 - 车道运行总览
          </h1>
          <p className="text-gray-600 text-sm">共 {lanes.length} 条车道 | 实时监控系统</p>
        </div>

        {/* 入口出口标识 + 图例 */}
        <div className="flex justify-between items-center mb-3 px-2">
          <div className="flex items-center gap-2 bg-gradient-to-r from-green-500 to-green-600 px-4 py-2 rounded-lg shadow-md">
            <ArrowRight className="w-5 h-5 text-white" />
            <span className="text-white font-bold">入口</span>
          </div>
          
          <div className="flex gap-4 bg-white px-4 py-2 rounded-lg shadow-md border border-gray-200">
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-red-500"></div>
              <span className="text-gray-700 text-xs">红灯</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-green-500"></div>
              <span className="text-gray-700 text-xs">绿灯</span>
            </div>
            <div className="flex items-center gap-1.5">
              <Car className="w-3 h-3 text-yellow-600" />
              <span className="text-gray-700 text-xs">出租车</span>
            </div>
          </div>

          <div className="flex items-center gap-2 bg-gradient-to-r from-blue-500 to-blue-600 px-4 py-2 rounded-lg shadow-md">
            <span className="text-white font-bold">出口</span>
            <ArrowLeft className="w-5 h-5 text-white" />
          </div>
        </div>

        {/* 车道列表 - 紧凑布局 */}
        <div className="flex-1 overflow-y-auto space-y-1.5 pr-2">
          {lanes.map((lane) => (
            <div
              key={lane.id}
              className="flex items-center gap-3"
            >
              {/* 左侧车道信息 */}
              <div className="flex flex-col gap-1 min-w-[100px]">
                {/* 车道标识 */}
                <div className="flex items-center gap-1.5">
                  <div
                    className="w-5 h-5 rounded shadow-sm"
                    style={{ backgroundColor: lane.color }}
                  ></div>
                  <h3 className="text-sm font-bold text-gray-800">{lane.name}</h3>
                </div>
                
                {/* 停车数据 */}
                <div className="flex items-center gap-1.5">
                  <div className="bg-gray-100 px-2 py-1 rounded text-xs">
                    <span className="text-gray-600">停车: </span>
                    <span className="text-gray-800 font-bold">
                      {lane.taxiCount}/{lane.capacity}
                    </span>
                  </div>
                  {isFull(lane) && (
                    <span className="px-1.5 py-0.5 rounded-full text-[9px] font-bold bg-red-500 text-white">
                      满
                    </span>
                  )}
                </div>
              </div>

              {/* 车道可视化区域 */}
              <div className="flex-1 relative">
                {/* 箭头形状的背景边框 */}
                <div 
                  className="absolute inset-0 shadow-sm transition-all"
                  style={{ 
                    background: canEnter(lane) && canExit(lane) ? '#22c55e' : canEnter(lane) || canExit(lane) ? '#f59e0b' : '#ef4444',
                    clipPath: 'polygon(0 0, calc(100% - 12px) 0, 100% 50%, calc(100% - 12px) 100%, 0 100%, 12px 50%)',
                  }}
                ></div>
                
                {/* 内部白色背景 */}
                <div 
                  className="absolute inset-0"
                  style={{ 
                    margin: '2px',
                    background: 'white',
                    clipPath: 'polygon(0 0, calc(100% - 12px) 0, 100% 50%, calc(100% - 12px) 100%, 0 100%, 12px 50%)',
                  }}
                ></div>
                
                {/* 内容区域 */}
                <div className="relative p-2">
                  <div className="flex items-center gap-2">
                    {/* 左侧红绿灯（入口） */}
                    <div className="flex flex-col items-center gap-0.5 min-w-[40px]">
                      <TrafficLight
                        status={lane.startLight}
                        onClick={() => toggleStartLight(lane.id)}
                        position="start"
                      />
                      <span className={`text-[9px] px-1 py-0.5 rounded font-medium ${canEnter(lane) ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                        {canEnter(lane) ? '可进' : '禁入'}
                      </span>
                    </div>

                    {/* 车道可视化 - 带箭头的车道 */}
                    <div className="relative h-8 flex-1">
                      {/* 箭头形状的车道 */}
                      <div 
                        className="absolute inset-0 shadow-inner overflow-hidden"
                        style={{ 
                          backgroundColor: lane.color + '22',
                          clipPath: 'polygon(0 0, calc(100% - 10px) 0, 100% 50%, calc(100% - 10px) 100%, 0 100%)',
                          borderRadius: '6px 0 0 6px'
                        }}
                      >
                        {/* 向右流动的箭头条纹动画 - 仅在出口灯为绿色时显示 */}
                        {lane.endLight === 'green' && (
                          <div 
                            className="absolute inset-0 opacity-50"
                            style={{
                              backgroundImage: `url("data:image/svg+xml,%3Csvg width='50' height='30' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M 0,0 L 35,0 L 50,15 L 35,30 L 0,30 L 15,15 Z' fill='${encodeURIComponent(lane.color)}'/%3E%3C/svg%3E")`,
                              backgroundRepeat: 'repeat-x',
                              backgroundSize: '50px 100%',
                              backgroundPosition: '0 center',
                              animation: 'flowRight 1.5s linear infinite'
                            }}
                          />
                        )}
                      </div>
                      
                      {/* 出租车图标 - 从左往右排列（先进先出）*/}
                      <div className="absolute inset-0 flex items-center justify-start pl-1.5 gap-0.5">
                        {Array.from({ length: lane.taxiCount }).map((_, index) => (
                          <div
                            key={index}
                            className="flex items-center justify-center w-7 h-7 bg-gradient-to-br from-yellow-400 to-yellow-500 rounded shadow-sm border border-yellow-600"
                            style={{ 
                              transform: `translateX(${index * 5}px)`,
                              zIndex: lane.taxiCount - index
                            }}
                          >
                            <Car className="w-4 h-4 text-gray-900" />
                          </div>
                        ))}
                      </div>
                    </div>

                    {/* 右侧红绿灯（出口） */}
                    <div className="flex flex-col items-center gap-0.5 min-w-[40px]">
                      <TrafficLight
                        status={lane.endLight}
                        onClick={() => toggleEndLight(lane.id)}
                        position="end"
                      />
                      <span className={`text-[9px] px-1 py-0.5 rounded font-medium ${canExit(lane) ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                        {canExit(lane) ? '可出' : '禁出'}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* 统计信息 */}
        <div className="mt-3 grid grid-cols-4 gap-2">
          <div className="bg-gradient-to-br from-green-400 to-green-500 p-3 rounded-lg shadow-md text-center">
            <div className="text-2xl font-bold text-white mb-0.5">
              {lanes.filter(lane => canEnter(lane)).length}
            </div>
            <div className="text-green-50 text-xs font-medium">可进入车道</div>
          </div>
          <div className="bg-gradient-to-br from-blue-400 to-blue-500 p-3 rounded-lg shadow-md text-center">
            <div className="text-2xl font-bold text-white mb-0.5">
              {lanes.filter(lane => canExit(lane)).length}
            </div>
            <div className="text-blue-50 text-xs font-medium">可出场车道</div>
          </div>
          <div className="bg-gradient-to-br from-yellow-400 to-yellow-500 p-3 rounded-lg shadow-md text-center">
            <div className="text-2xl font-bold text-white mb-0.5">
              {lanes.reduce((sum, lane) => sum + lane.taxiCount, 0)}
            </div>
            <div className="text-yellow-50 text-xs font-medium">出租车总数</div>
          </div>
          <div className="bg-gradient-to-br from-red-400 to-red-500 p-3 rounded-lg shadow-md text-center">
            <div className="text-2xl font-bold text-white mb-0.5">
              {lanes.filter(lane => isFull(lane)).length}
            </div>
            <div className="text-red-50 text-xs font-medium">满载车道数</div>
          </div>
        </div>
      </div>
    </div>
  );
}