import { useEffect, useMemo, useRef, useState } from 'react'
import neon from '../../app/src/main/assets/skins/neon-grid/skin.json'
import garden from '../../app/src/main/assets/skins/pixel-garden/skin.json'
import studio from '../../app/src/main/assets/skins/studio-deck/skin.json'
import { hitRegion, renderTheme, type HitRegion } from './engine/renderer'
import { validateTheme } from './engine/validator'
import type { PreviewState, RenderAsset, SkinDocument, SkinNode } from './engine/types'

const clone=<T,>(x:T):T=>JSON.parse(JSON.stringify(x))
const remix=(source:unknown)=>{const value=clone(source as SkinDocument);value.id+= '-remix';value.name+=' Remix';value.author='';return value}
const templates:Record<string,SkinDocument>={'Neon Grid':remix(neon),'Pixel Garden':remix(garden),'Studio Deck':remix(studio)}
const blank:SkinDocument={formatVersion:2,id:'my-theme',name:'My Theme',author:'',viewport:[480,360],font:'sans',colors:{background:'#10141c',surface:'#202938',primaryText:'#f5f7fa',secondaryText:'#aeb8c8',accent:'#55d6be',focusSurface:'#30485a',warning:'#ff6b6b'},components:{},screens:{default:[{type:'text',bounds:[20,20,440,40],text:'{screen.title}',size:24,bold:true,color:'accent'},{type:'rows',bounds:[20,72,440,250],rowHeight:48,gap:6,children:[{type:'rect',bounds:[0,0,440,48],anchor:'stretch',color:'surface',radius:8,states:{focused:{color:'focusSurface',borderColor:'accent',borderWidth:2}}},{type:'text',bounds:[14,0,410,48],text:'{row.title}',size:17}]}]}}
const initial=()=>{try{return JSON.parse(localStorage.getItem('y2-theme-studio.project')||'null') as SkinDocument||clone(templates['Neon Grid'])}catch{return clone(templates['Neon Grid'])}}
const initialPreview:PreviewState={screen:'main_menu',selected:0,playing:true,charging:false,empty:false,message:false,alphabet:false,progress:.25,volume:65,query:''}
const nodeTypes=['rect','text','image','artwork','progress','rows','group','keyboard','icon','circle','line','path']
const download=(blob:Blob,name:string)=>{const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000)}
const makeAsset=async(path:string,file:Blob):Promise<RenderAsset>=>{const kind=/\.(ttf|otf)$/i.test(path)?'font':'image',url=URL.createObjectURL(file);if(kind==='font'){const face=new FontFace(path,`url(${url})`);await face.load();document.fonts.add(face)}return {kind,url,file}}

export default function App(){
  const [theme,setTheme]=useState<SkinDocument>(initial),[assets,setAssets]=useState<Record<string,RenderAsset>>({})
  const [preview,setPreview]=useState(initialPreview),[selected,setSelected]=useState(''),[undo,setUndo]=useState<SkinDocument[]>([]),[redo,setRedo]=useState<SkinDocument[]>([])
  const [rawOpen,setRawOpen]=useState(false),[raw,setRaw]=useState(''),[notice,setNotice]=useState('Ready')
  const canvas=useRef<HTMLCanvasElement>(null),regions=useRef<HitRegion[]>([]),drag=useRef<{x:number;y:number;path:string;bounds:[number,number,number,number]}|null>(null)
  const validation=useMemo(()=>validateTheme(theme,assets),[theme,assets]),compiled=validation.compiled??theme
  const screens=Object.keys(theme.screens)
  useEffect(()=>{if(!screens.includes(preview.screen))setPreview(p=>({...p,screen:screens[0]}))},[screens.join('|')])
  useEffect(()=>{localStorage.setItem('y2-theme-studio.project',JSON.stringify(theme))},[theme])
  useEffect(()=>{if(canvas.current)regions.current=renderTheme(canvas.current,compiled,preview,assets,selected)},[compiled,preview,assets,selected])
  const commit=(fn:(draft:SkinDocument)=>void)=>{setUndo(u=>[...u.slice(-49),clone(theme)]);setRedo([]);const next=clone(theme);fn(next);setTheme(next)}
  const screenNodes=theme.screens[preview.screen]??theme.screens.default
  const selectedIndex=Number(selected.match(new RegExp(`^screens\\.${preview.screen}\\.(\\d+)$`))?.[1]??-1),selectedNode=selectedIndex>=0?screenNodes[selectedIndex]:undefined
  const selectNode=(index:number)=>setSelected(`screens.${preview.screen}.${index}`)
  const undoNow=()=>{const prev=undo.at(-1);if(prev){setRedo(r=>[clone(theme),...r]);setTheme(prev);setUndo(u=>u.slice(0,-1));setSelected('')}}
  const redoNow=()=>{const next=redo[0];if(next){setUndo(u=>[...u,clone(theme)]);setTheme(next);setRedo(r=>r.slice(1));setSelected('')}}
  const template=(name:string)=>{setUndo(u=>[...u,clone(theme)]);setTheme(name==='Blank'?clone(blank):clone(templates[name]));setAssets({});setSelected('');setNotice(`${name} loaded`)}
  const addNode=()=>commit(d=>{const list=d.screens[preview.screen]??d.screens.default;list.push({type:'rect',bounds:[40,40,160,60],color:'surface',borderColor:'accent',borderWidth:1,radius:6});setTimeout(()=>selectNode(list.length-1))})
  const deleteNode=()=>selectedIndex>=0&&commit(d=>{(d.screens[preview.screen]??d.screens.default).splice(selectedIndex,1);setSelected('')})
  const duplicateNode=()=>selectedNode&&commit(d=>{const n=clone(selectedNode);n.bounds=[n.bounds[0]+8,n.bounds[1]+8,n.bounds[2],n.bounds[3]];(d.screens[preview.screen]??d.screens.default).splice(selectedIndex+1,0,n);setTimeout(()=>selectNode(selectedIndex+1))})
  const moveNode=(delta:number)=>selectedNode&&commit(d=>{const list=d.screens[preview.screen]??d.screens.default,to=Math.max(0,Math.min(list.length-1,selectedIndex+delta));list.splice(to,0,...list.splice(selectedIndex,1));setTimeout(()=>selectNode(to))})
  const setNode=(key:string,value:unknown)=>selectedNode&&commit(d=>{((d.screens[preview.screen]??d.screens.default)[selectedIndex] as unknown as Record<string,unknown>)[key]=value})
  const setBound=(i:number,value:number)=>selectedNode&&commit(d=>{(d.screens[preview.screen]??d.screens.default)[selectedIndex].bounds[i]=value})
  const canvasPoint=(e:React.PointerEvent<HTMLCanvasElement>)=>{const r=e.currentTarget.getBoundingClientRect();return {x:(e.clientX-r.left)*theme.viewport[0]/r.width,y:(e.clientY-r.top)*theme.viewport[1]/r.height}}
  const pointerDown=(e:React.PointerEvent<HTMLCanvasElement>)=>{const p=canvasPoint(e),hit=hitRegion(regions.current,p.x,p.y);if(!hit)return;const top=hit.path.match(new RegExp(`^screens\\.${preview.screen}\\.(\\d+)`));if(!top)return;const path=`screens.${preview.screen}.${top[1]}`;setSelected(path);const n=screenNodes[Number(top[1])];drag.current={...p,path,bounds:[...n.bounds]};setUndo(u=>[...u.slice(-49),clone(theme)]);setRedo([]);e.currentTarget.setPointerCapture(e.pointerId)}
  const pointerMove=(e:React.PointerEvent<HTMLCanvasElement>)=>{if(!drag.current)return;const p=canvasPoint(e),d=drag.current,index=Number(d.path.split('.').at(-1));setTheme(old=>{const next=clone(old),n=(next.screens[preview.screen]??next.screens.default)[index];n.bounds[0]=Math.max(0,Math.round(d.bounds[0]+p.x-d.x));n.bounds[1]=Math.max(0,Math.round(d.bounds[1]+p.y-d.y));return next})}
  const pointerUp=()=>{drag.current=null}
  const importFiles=async(files:FileList|null)=>{if(!files?.length)return;let doc:SkinDocument|undefined;const nextAssets:Record<string,RenderAsset>={}
    for(const file of [...files]){if(file.name.endsWith('.zip')){const JSZip=(await import('jszip')).default,zip=await JSZip.loadAsync(file),paths=Object.keys(zip.files),manifest=paths.find(path=>path==='skin.json'||path.endsWith('/skin.json')),root=manifest?.slice(0,-'skin.json'.length)??'';for(const [path,entry] of Object.entries(zip.files)){if(entry.dir)continue;const rel=root&&path.startsWith(root)?path.slice(root.length):path;if(rel==='skin.json')doc=JSON.parse(await entry.async('text'));else nextAssets[rel]=await makeAsset(rel,await entry.async('blob'))}}else{const rel=(file.webkitRelativePath||file.name).split('/').slice(file.webkitRelativePath?1:0).join('/');if(file.name==='skin.json'||(files.length===1&&file.name.endsWith('.json')))doc=JSON.parse(await file.text());else nextAssets[rel]=await makeAsset(rel,file)}}
    if(doc){setUndo(u=>[...u,clone(theme)]);setTheme(doc);setAssets(nextAssets);setSelected('');setNotice(`Imported ${doc.name}`)}else if(Object.keys(nextAssets).length){setAssets(old=>({...old,...nextAssets}));setNotice(`Added ${Object.keys(nextAssets).length} assets`)}else setNotice('No skin.json found')}
  const exportZip=async()=>{const checked=validateTheme(theme,assets);if(checked.errors.some(e=>e.severity==='error')){setNotice('Fix validation errors before export');return}const JSZip=(await import('jszip')).default,zip=new JSZip(),folder=zip.folder(theme.id)!;folder.file('skin.json',JSON.stringify(theme,null,2));for(const [path,a] of Object.entries(assets))if(a.file)folder.file(path,a.file);folder.file('README.txt',`Created with Y2ThemeStudio. Extract this folder into Y2Player/Skins.\n`);download(await zip.generateAsync({type:'blob'}),`${theme.id}.zip`);setNotice('Theme ZIP exported')}
  const capture=()=>canvas.current?.toBlob(b=>b&&download(b,`${theme.id}-${preview.screen}.png`))
  const applyRaw=()=>{try{const parsed=JSON.parse(raw);setUndo(u=>[...u,clone(theme)]);setTheme(parsed);setRawOpen(false);setNotice('JSON applied')}catch(e){setNotice((e as Error).message)}}
  return <div className="app">
    <header><div className="brand"><span className="mark">Y2</span><div><h1>Y2ThemeStudio</h1><small>Design skins for Y2Player</small></div></div>
      <div className="toolbar">
        <select aria-label="Template" defaultValue="" onChange={e=>{if(e.target.value)template(e.target.value);e.target.value='' }}><option value="" disabled>New from…</option><option>Blank</option>{Object.keys(templates).map(x=><option key={x}>{x}</option>)}</select>
        <label className="button">Import<input hidden type="file" multiple accept=".json,.zip,image/*,.ttf,.otf" onChange={e=>importFiles(e.target.files)}/></label>
        <label className="button">Open folder<input hidden type="file" multiple {...{webkitdirectory:''}} onChange={e=>importFiles(e.target.files)}/></label>
        <label className="button">Add assets<input hidden type="file" multiple accept="image/*,.ttf,.otf" onChange={e=>importFiles(e.target.files)}/></label>
        <button onClick={undoNow} disabled={!undo.length}>Undo</button><button onClick={redoNow} disabled={!redo.length}>Redo</button>
        <button onClick={()=>{setRaw(JSON.stringify(theme,null,2));setRawOpen(true)}}>JSON</button><button onClick={capture}>PNG</button><button className="primary" onClick={exportZip}>Export ZIP</button>
      </div>
    </header>
    <main>
      <aside className="left panel">
        <section><h2>Project</h2><label>Name<input value={theme.name} onChange={e=>commit(d=>{d.name=e.target.value})}/></label><label>ID<input value={theme.id} onChange={e=>commit(d=>{d.id=e.target.value})}/></label><label>Author<input value={theme.author??''} onChange={e=>commit(d=>{d.author=e.target.value})}/></label></section>
        <section><div className="section-title"><h2>Screens</h2><button onClick={()=>commit(d=>{const name=`screen_${Object.keys(d.screens).length}`;d.screens[name]=[];setPreview(p=>({...p,screen:name}))})}>+</button></div>
          <div className="screen-list">{screens.map(s=><button className={preview.screen===s?'active':''} key={s} onClick={()=>{setPreview(p=>({...p,screen:s}));setSelected('')}}>{s.replaceAll('_',' ')}</button>)}</div></section>
        <section><div className="section-title"><h2>Layers</h2><button onClick={addNode}>+</button></div>
          <div className="layers">{screenNodes.map((n,i)=><button key={i} className={selectedIndex===i?'active':''} onClick={()=>selectNode(i)}><span>{i+1}</span>{n.type}<small>{n.text?.slice(0,18)||n.asset||''}</small></button>)}</div>
          <div className="row-actions"><button onClick={()=>moveNode(-1)} disabled={!selectedNode}>↑</button><button onClick={()=>moveNode(1)} disabled={!selectedNode}>↓</button><button onClick={duplicateNode} disabled={!selectedNode}>Duplicate</button><button onClick={deleteNode} disabled={!selectedNode}>Delete</button></div>
        </section>
      </aside>
      <section className="workspace">
        <div className="preview-toolbar"><strong>{theme.name}</strong><span>480 × 360</span><label>Screen<select value={preview.screen} onChange={e=>setPreview(p=>({...p,screen:e.target.value}))}>{screens.map(s=><option key={s}>{s}</option>)}</select></label></div>
        <div className="stage"><canvas ref={canvas} onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerUp}/></div>
        <div className="simulator">
          <label><input type="checkbox" checked={preview.playing} onChange={e=>setPreview(p=>({...p,playing:e.target.checked}))}/> Playing</label>
          <label><input type="checkbox" checked={preview.charging} onChange={e=>setPreview(p=>({...p,charging:e.target.checked}))}/> Charging</label>
          <label><input type="checkbox" checked={preview.empty} onChange={e=>setPreview(p=>({...p,empty:e.target.checked}))}/> Empty</label>
          <label><input type="checkbox" checked={preview.message} onChange={e=>setPreview(p=>({...p,message:e.target.checked}))}/> Message</label>
          <label><input type="checkbox" checked={preview.alphabet} onChange={e=>setPreview(p=>({...p,alphabet:e.target.checked}))}/> Alphabet</label>
          <label>Selection <input type="range" min="0" max="6" value={preview.selected} onChange={e=>setPreview(p=>({...p,selected:+e.target.value}))}/></label>
          <label>Progress <input type="range" min="0" max="1" step=".01" value={preview.progress} onChange={e=>setPreview(p=>({...p,progress:+e.target.value}))}/></label>
          <label>Volume <input type="range" min="0" max="100" value={preview.volume} onChange={e=>setPreview(p=>({...p,volume:+e.target.value}))}/></label>
        </div>
        <div className="status"><span className={validation.errors.some(e=>e.severity==='error')?'bad':'good'}>{validation.errors.filter(e=>e.severity==='error').length} errors</span><span>{validation.errors.filter(e=>e.severity==='warning').length} warnings</span><span>{notice}</span></div>
      </section>
      <aside className="right panel">
        {selectedNode?<><section><h2>Inspector</h2><label>Type<select value={selectedNode.type} onChange={e=>setNode('type',e.target.value)}>{nodeTypes.map(t=><option key={t}>{t}</option>)}</select></label>
          <div className="bounds">{['X','Y','W','H'].map((x,i)=><label key={x}>{x}<input type="number" value={selectedNode.bounds[i]} onChange={e=>setBound(i,+e.target.value)}/></label>)}</div>
          {selectedNode.type==='text'&&<label>Text<textarea value={selectedNode.text??''} onChange={e=>setNode('text',e.target.value)}/></label>}
          <label>Color<select value={selectedNode.color??'primaryText'} onChange={e=>setNode('color',e.target.value)}>{Object.keys(theme.colors).map(c=><option key={c}>{c}</option>)}</select></label>
          <label>Background<select value={selectedNode.background??'surface'} onChange={e=>setNode('background',e.target.value)}>{Object.keys(theme.colors).map(c=><option key={c}>{c}</option>)}</select></label>
          <div className="bounds"><label>Size<input type="number" value={selectedNode.size??16} onChange={e=>setNode('size',+e.target.value)}/></label><label>Radius<input type="number" value={selectedNode.radius??0} onChange={e=>setNode('radius',+e.target.value)}/></label></div>
          <label>Action<input value={selectedNode.action??''} onChange={e=>setNode('action',e.target.value)}/></label><label>Visible when<input value={selectedNode.when??'always'} onChange={e=>setNode('when',e.target.value)}/></label>
        </section></>:<section className="hint"><h2>Inspector</h2><p>Select a layer or click the preview. Drag a selected top-level element to reposition it.</p></section>}
        <section><h2>Palette</h2><div className="palette">{Object.entries(theme.colors).map(([name,color])=><label key={name}><input type="color" value={color.length===7?color:'#ffffff'} onChange={e=>commit(d=>{d.colors[name]=e.target.value})}/><span>{name}</span><input value={color} onChange={e=>commit(d=>{d.colors[name]=e.target.value})}/></label>)}</div><button onClick={()=>commit(d=>{d.colors[`color${Object.keys(d.colors).length+1}`]='#ffffff'})}>Add color</button></section>
        <section><h2>Validation</h2><div className="errors">{validation.errors.length?validation.errors.map((e,i)=><div className={e.severity} key={i}><strong>{e.path}</strong>{e.message}</div>):<p className="all-good">Theme is valid and ready to export.</p>}</div></section>
      </aside>
    </main>
    {rawOpen&&<div className="modal" role="dialog" aria-modal="true"><div><div className="section-title"><h2>Raw skin.json</h2><button onClick={()=>setRawOpen(false)}>Close</button></div><textarea value={raw} onChange={e=>setRaw(e.target.value)} spellCheck={false}/><footer><button onClick={()=>navigator.clipboard.writeText(raw)}>Copy</button><button className="primary" onClick={applyRaw}>Apply JSON</button></footer></div></div>}
  </div>
}
