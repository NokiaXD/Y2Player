import fs from 'node:fs';
import assert from 'node:assert/strict';
import * as g from './geometry.mjs';
const fixtures=JSON.parse(fs.readFileSync(new URL('../../docs/skins/preview-fixtures.json',import.meta.url)));
const near=(a,b)=>{if(Array.isArray(a)){assert.equal(a.length,b.length);a.forEach((v,i)=>near(v,b[i]));}else assert.ok(Math.abs(a-b)<.001,`${a} != ${b}`);};
for(const f of fixtures.lists){assert.equal(g.capacity(f.node),f.capacity);assert.equal(g.firstVisible(f.node,f.selected),f.first);near(g.cell(f.node,f.slot),f.cell);}
for(const f of fixtures.images)near(g.imageBounds(f.box,f.width,f.height,f.fit,f.x,f.y),f.result);
for(const f of fixtures.seek)near(g.seekFraction(f.box,f.orientation,f.x,f.y),f.result);
for(const f of fixtures.layouts)near(g.children(f.node).map(n=>n.bounds),f.result);
for(const f of fixtures.text)assert.deepEqual(g.textLines(f.source,f.width,f.count,f.ellipsis,s=>[...s].length),f.result);
console.log('Browser geometry and text fixture checks passed.');
