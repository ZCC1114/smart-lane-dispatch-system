interface TrafficLightProps {
  status: 'red' | 'green';
  onClick: () => void;
  position: 'start' | 'end';
}

export function TrafficLight({ status, onClick, position }: TrafficLightProps) {
  return (
    <button
      onClick={onClick}
      className="w-7 h-7 rounded-full border-2 border-gray-800 shadow-md transition-all hover:scale-110 cursor-pointer"
      style={{
        backgroundColor: status === 'red' ? '#ef4444' : '#22c55e',
        boxShadow: status === 'red' 
          ? '0 0 10px rgba(239, 68, 68, 0.7)' 
          : '0 0 10px rgba(34, 197, 94, 0.7)'
      }}
      title={position === 'start' ? '点击切换入口红绿灯' : '点击切换出口红绿灯'}
    />
  );
}