import { describe,expect,it } from 'vitest'
import neon from '../../../app/src/main/assets/skins/neon-grid/skin.json'
import garden from '../../../app/src/main/assets/skins/pixel-garden/skin.json'
import studio from '../../../app/src/main/assets/skins/studio-deck/skin.json'
import fixtures from '../../../docs/skins/preview-fixtures.json'
import { compileSkin } from '../engine/compiler'
import { capacity,cell,firstVisible,imageBounds,layoutChildren } from '../engine/geometry'
import { validateTheme } from '../engine/validator'
import type { SkinDocument,SkinNode } from '../engine/types'

describe('shared Y2 skin contract',()=>{
  it('validates and compiles all bundled v2 skins',()=>{
    for(const source of [neon,garden,studio] as unknown as SkinDocument[]){const result=validateTheme(source,{},true);expect(result.errors.filter(e=>e.severity==='error')).toEqual([]);expect(result.compiled?.formatVersion).toBe(2);expect(result.compiled?.screens.now_playing.length).toBeGreaterThan(5)}
  })
  it('expands styles and typed component parameters',()=>{
    const source=structuredClone(neon) as unknown as SkinDocument,compiled=compileSkin(source)
    const flatten=(nodes:SkinNode[]):SkinNode[]=>nodes.flatMap(n=>[n,...flatten(n.children??[])])
    expect(flatten(compiled.screens.now_playing).some(n=>n.focus?.id==='play')).toBe(true)
  })
  it('matches Android geometry fixtures',()=>{
    for(const f of fixtures.lists){const n=f.node as SkinNode;expect(capacity(n)).toBe(f.capacity);expect(firstVisible(n,f.selected)).toBe(f.first);expect(cell(n,f.slot)).toEqual(f.cell)}
    for(const f of fixtures.images)expect(imageBounds(f.box as [number,number,number,number],f.width,f.height,f.fit,f.x,f.y)).toEqual(f.result)
    for(const f of fixtures.layouts)expect(layoutChildren(f.node as SkinNode).map(n=>n.bounds)).toEqual(f.result)
  })
  it('reports structural and semantic mistakes',()=>{const source=structuredClone(neon) as unknown as SkinDocument;source.screens.default[0].action='execute';expect(validateTheme(source).errors.some(e=>e.message.includes('Unknown action'))).toBe(true)})
})
