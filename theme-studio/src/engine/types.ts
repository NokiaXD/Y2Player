export type Bounds = [number, number, number, number]
export type Scalar = string | number | boolean
export type Visual = Partial<Pick<SkinNode, 'color'|'background'|'opacity'|'borderColor'|'borderWidth'|'radius'|'size'|'bold'>>
export interface Focus { id:string; group?:string; order?:number; previous?:string; next?:string }
export interface Gradient { endColor:string; direction?:'horizontal'|'vertical' }
export interface KeyStyle { gap?:number; radius?:number; color?:string; background?:string; focusColor?:string; focusBackground?:string; borderColor?:string; borderWidth?:number }
export interface SkinNode {
  type:string; bounds:Bounds; text?:string; color?:string; background?:string; size?:number; radius?:number; stroke?:number
  align?:'left'|'center'|'right'; bold?:boolean; when?:string; action?:string; asset?:string; columns?:number; rowHeight?:number
  gap?:number; segments?:number; children?:SkinNode[]; lines?:number; font?:string; lineSpacing?:number; overflow?:'ellipsis'|'clip'|'marquee'
  verticalAlign?:'top'|'center'|'bottom'; imageFit?:'stretch'|'fit'|'crop'; imageX?:number; imageY?:number; opacity?:number
  borderColor?:string; borderWidth?:number; gradient?:Gradient; points?:[number,number][]; closed?:boolean
  orientation?:'horizontal'|'vertical'|'circular'; seekable?:boolean; layout?:'absolute'|'row'|'column'|'grid'|'stack'
  padding?:number; anchor?:'topLeft'|'topRight'|'bottomLeft'|'bottomRight'|'center'|'stretch'; weight?:number
  listMode?:'grid'|'horizontal'|'carousel'|'radial'; rowWidth?:number; scrolling?:'page'|'follow'|'center'
  keyStyle?:KeyStyle; states?:Record<string,Visual>; focus?:Focus; disabled?:boolean; transitionMs?:number; marqueeSpeed?:number
  style?:string; props?:Record<string,Scalar>
}
export interface ComponentTemplate { parameters?:Record<string,Scalar>; nodes:SkinNode[] }
export interface SkinDocument {
  formatVersion:2; id:string; name:string; author?:string; viewport:[number,number]; font?:string
  colors:Record<string,string>; styles?:Record<string,Visual & {font?:string;lineSpacing?:number;overflow?:string;verticalAlign?:string}>
  components?:Record<string,SkinNode[]|ComponentTemplate>; screens:Record<string,SkinNode[]>
  navigation?:Record<string,{initial?:string;wrap?:boolean}>
}
export interface PreviewState {
  screen:string; selected:number; playing:boolean; charging:boolean; empty:boolean; message:boolean; alphabet:boolean
  progress:number; volume:number; query:string
}
export interface RenderAsset { url:string; kind:'image'|'font'; file?:Blob }
export interface ThemeProject { theme:SkinDocument; assets:Record<string,RenderAsset> }
export interface EditorError { path:string; message:string; severity:'error'|'warning' }
export interface RowData { title:string; subtitle:string; trailing?:string; active?:boolean; unavailable?:boolean }
