// Browser-compatible, dependency-free counterpart of SkinLayout / SkinTextLayout.
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
export function capacity(n) {
  const [,, w, h] = n.bounds, gap = n.gap ?? 4;
  return clamp(n.listMode === 'radial' ? n.columns ?? 1 :
    ['horizontal', 'carousel'].includes(n.listMode) ? Math.floor((w + gap) / ((n.rowWidth ?? 96) + gap)) :
    Math.floor((h + gap) / ((n.rowHeight ?? 48) + gap)) * (n.columns ?? 1), 1, 128);
}
export function firstVisible(n, selected) {
  const c = capacity(n), i = Math.max(0, selected);
  if (n.listMode === 'carousel' || n.scrolling === 'center') return Math.max(0, i - Math.floor(c / 2));
  if (n.scrolling === 'follow') return Math.max(0, i - c + 1);
  return Math.floor(i / c) * c;
}
export function cell(n, slot) {
  const [x,y,w,h] = n.bounds, gap = n.gap ?? 4, rh = n.rowHeight ?? 48, rw = n.rowWidth ?? 96, cols = n.columns ?? 1;
  if (['horizontal','carousel'].includes(n.listMode)) return [x + slot * (rw + gap), y, rw, rh];
  if (n.listMode === 'radial') {
    const angle = -Math.PI / 2 + slot * 2 * Math.PI / capacity(n);
    return [x + (w-rw)/2 * (1+Math.cos(angle)), y + (h-rh)/2 * (1+Math.sin(angle)), rw, rh];
  }
  const cw = (w - (cols-1)*gap)/cols;
  return [x + slot%cols*(cw+gap), y + Math.floor(slot/cols)*(rh+gap), cw, rh];
}
export function children(parent) {
  const p=parent.padding??0, w=Math.max(1,parent.bounds[2]-2*p),h=Math.max(1,parent.bounds[3]-2*p), nodes=parent.children??[],gap=parent.gap??4;
  const horizontal=parent.layout==='row', fixed=nodes.filter(n=>!n.weight).reduce((sum,n)=>sum+n.bounds[horizontal?2:3],0), weights=nodes.reduce((sum,n)=>sum+(n.weight??0),0);
  const available=Math.max(0,(horizontal?w:h)-fixed-gap*Math.max(0,nodes.length-1));let cursor=p;
  return nodes.map((n,i)=>{
    const [x,y,nw,nh]=n.bounds; let b;
    if (['row','column'].includes(parent.layout)) {
      const extent=n.weight?Math.max(1,available*n.weight/weights):horizontal?nw:nh;
      b=horizontal?[cursor,p,extent,h]:[p,cursor,w,extent];cursor+=extent+gap;
    } else if(parent.layout==='grid') {
      const cols=parent.columns??1,rows=Math.max(1,Math.ceil(nodes.length/cols)),cw=Math.max(1,(w-gap*(cols-1))/cols),ch=Math.max(1,(h-gap*(rows-1))/rows);
      b=[p+i%cols*(cw+gap),p+Math.floor(i/cols)*(ch+gap),cw,ch];
    } else if(parent.layout==='stack') b=[p,p,w,h];
    else b=({topRight:[p+w-nw-x,p+y,nw,nh],bottomLeft:[p+x,p+h-nh-y,nw,nh],bottomRight:[p+w-nw-x,p+h-nh-y,nw,nh],center:[p+(w-nw)/2+x,p+(h-nh)/2+y,nw,nh],stretch:[p+x,p+y,Math.max(1,w-2*x),Math.max(1,h-2*y)]})[n.anchor]??[p+x,p+y,nw,nh];
    return {...n,bounds:b};
  });
}
export function imageBounds(box, width, height, fit, x=.5, y=.5) {
  if (fit==='stretch') return box;
  const scale=(fit==='crop'?Math.max:Math.min)(box[2]/width,box[3]/height),w=width*scale,h=height*scale;
  return [box[0]+(box[2]-w)*x,box[1]+(box[3]-h)*y,w,h];
}
export function seekFraction(b, orientation, x, y) {
  if(orientation==='vertical') return clamp(1-(y-b[1])/b[3],0,1);
  if(orientation==='circular') return ((Math.atan2(y-b[1]-b[3]/2,x-b[0]-b[2]/2)+Math.PI/2+2*Math.PI)%(2*Math.PI))/(2*Math.PI);
  return clamp((x-b[0])/b[2],0,1);
}
export function textLines(source,width,count,ellipsis,measure) {
  let remaining=source;const result=[];
  for(let line=0;line<Math.max(1,count);line++) {
    if(!remaining)break;
    const paragraph=remaining.split('\n')[0],last=line===count-1;
    if(measure(paragraph)<=width&&(!last||paragraph.length===remaining.length)) {
      result.push(paragraph);remaining=remaining.slice(paragraph.length+(paragraph.length<remaining.length?1:0));
    }else{
      const suffix=last&&ellipsis?'…':'',available=Math.max(0,width-measure(suffix));let end=0;
      for(const c of paragraph){if(measure(paragraph.slice(0,end+c.length))>available)break;end+=c.length;}
      if(!last&&end<paragraph.length){const space=paragraph.lastIndexOf(' ',Math.max(0,end-1));if(space>0)end=space;}
      result.push(paragraph.slice(0,end)+(measure(suffix)<=width?suffix:''));
      if(!end&&paragraph)end=[...paragraph][0].length;
      remaining=remaining.slice(end).replace(/^[ \n]+/,'');
    }
  }
  return result;
}
