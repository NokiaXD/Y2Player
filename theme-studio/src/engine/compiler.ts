import type { ComponentTemplate, Scalar, SkinDocument, SkinNode } from './types'

const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value))
const token=/\{param\.([a-zA-Z][a-zA-Z0-9_]*)\}/g
function substitute(value:unknown,props:Record<string,Scalar>):unknown {
  if(Array.isArray(value))return value.map(v=>substitute(v,props))
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,substitute(v,props)]))
  if(typeof value==='string'){
    const whole=value.match(/^\{param\.([a-zA-Z][a-zA-Z0-9_]*)\}$/)
    if(whole){if(!(whole[1] in props))throw new Error(`Missing component parameter: ${whole[1]}`);return props[whole[1]]}
    return value.replace(token,(_,key:string)=>{if(!(key in props))throw new Error(`Missing component parameter: ${key}`);return String(props[key])})
  }
  return value
}

export function compileSkin(source:SkinDocument):SkinDocument {
  const document=clone(source),templates=document.components??{},styles=document.styles??{};let budget=0
  const expand=(nodes:SkinNode[],stack:string[]=[]):SkinNode[]=>nodes.map(raw=>{
    if(++budget>4096)throw new Error('Compiled skin exceeds 4096 nodes')
    let node={...(raw.style?styles[raw.style]??(()=>{throw new Error(`Unknown style: ${raw.style}`)})():{}),...clone(raw)} as SkinNode
    delete node.style
    if(node.type==='component'){
      const key=node.asset??'';if(stack.includes(key))throw new Error(`Component cycle: ${[...stack,key].join(' → ')}`)
      const template=templates[key];if(!template)throw new Error(`Unknown component: ${key}`)
      const structured=!Array.isArray(template),definition=(structured?template:{nodes:template}) as ComponentTemplate
      const defaults=definition.parameters??{},overrides=node.props??{}
      for(const [name,value] of Object.entries(overrides)){if(!(name in defaults))throw new Error(`Unknown component parameter: ${name}`);if(typeof defaults[name]!==typeof value)throw new Error(`Parameter type mismatch: ${name}`)}
      const props={...defaults,...overrides};delete node.props
      return {...node,type:'group',asset:undefined,children:expand(substitute(definition.nodes,props) as SkinNode[],[...stack,key])}
    }
    if(node.children)node.children=expand(node.children,stack)
    return node
  })
  document.screens=Object.fromEntries(Object.entries(document.screens).map(([k,v])=>[k,expand(v)]))
  return document
}
