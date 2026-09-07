import type { Bounds, SkinNode } from './types'
const clamp=(v:number,lo:number,hi:number)=>Math.min(hi,Math.max(lo,v))
export function capacity(n:SkinNode) {
  const [,,w,h]=n.bounds,g=n.gap??4
  return clamp(n.listMode==='radial'?(n.columns??1):['horizontal','carousel'].includes(n.listMode??'')?
    Math.floor((w+g)/((n.rowWidth??96)+g)):Math.floor((h+g)/((n.rowHeight??48)+g))*(n.columns??1),1,128)
}
export function firstVisible(n:SkinNode,selected:number) {
  const c=capacity(n),i=Math.max(0,selected)
  if(n.listMode==='carousel'||n.scrolling==='center')return Math.max(0,i-Math.floor(c/2))
  if(n.scrolling==='follow')return Math.max(0,i-c+1)
  return Math.floor(i/c)*c
}
export function cell(n:SkinNode,slot:number):Bounds {
  const [x,y,w,h]=n.bounds,g=n.gap??4,rh=n.rowHeight??48,rw=n.rowWidth??96,cols=n.columns??1
  if(n.listMode==='horizontal'||n.listMode==='carousel')return [x+slot*(rw+g),y,rw,rh]
  if(n.listMode==='radial'){const a=-Math.PI/2+slot*2*Math.PI/capacity(n);return [x+(w-rw)/2*(1+Math.cos(a)),y+(h-rh)/2*(1+Math.sin(a)),rw,rh]}
  const cw=(w-(cols-1)*g)/cols
  return [x+(slot%cols)*(cw+g),y+Math.floor(slot/cols)*(rh+g),cw,rh]
}
export function layoutChildren(parent:SkinNode):SkinNode[] {
  const p=parent.padding??0,w=Math.max(1,parent.bounds[2]-2*p),h=Math.max(1,parent.bounds[3]-2*p),nodes=parent.children??[],g=parent.gap??4
  const horizontal=parent.layout==='row',fixed=nodes.filter(n=>!n.weight).reduce((s,n)=>s+n.bounds[horizontal?2:3],0)
  const weights=nodes.reduce((s,n)=>s+(n.weight??0),0),available=Math.max(0,(horizontal?w:h)-fixed-g*Math.max(0,nodes.length-1));let cursor=p
  return nodes.map((n,i)=>{const [x,y,nw,nh]=n.bounds;let b:Bounds
    if(parent.layout==='row'||parent.layout==='column'){const extent=n.weight?Math.max(1,available*(n.weight??0)/weights):(horizontal?nw:nh);b=horizontal?[cursor,p,extent,h]:[p,cursor,w,extent];cursor+=extent+g}
    else if(parent.layout==='grid'){const cols=parent.columns??1,rows=Math.max(1,Math.ceil(nodes.length/cols)),cw=Math.max(1,(w-g*(cols-1))/cols),ch=Math.max(1,(h-g*(rows-1))/rows);b=[p+i%cols*(cw+g),p+Math.floor(i/cols)*(ch+g),cw,ch]}
    else if(parent.layout==='stack')b=[p,p,w,h]
    else {const map:Record<string,Bounds>={topRight:[p+w-nw-x,p+y,nw,nh],bottomLeft:[p+x,p+h-nh-y,nw,nh],bottomRight:[p+w-nw-x,p+h-nh-y,nw,nh],center:[p+(w-nw)/2+x,p+(h-nh)/2+y,nw,nh],stretch:[p+x,p+y,Math.max(1,w-2*x),Math.max(1,h-2*y)]};b=map[n.anchor??'']??[p+x,p+y,nw,nh]}
    return {...n,bounds:b}
  })
}
export function imageBounds(box:Bounds,width:number,height:number,fit:string,x=.5,y=.5):Bounds {
  if(fit==='stretch')return box;const scale=(fit==='crop'?Math.max:Math.min)(box[2]/width,box[3]/height),w=width*scale,h=height*scale
  return [box[0]+(box[2]-w)*x,box[1]+(box[3]-h)*y,w,h]
}
