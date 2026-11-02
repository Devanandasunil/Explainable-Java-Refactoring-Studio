const editor=document.getElementById("source"),out=document.getElementById("output");
const sample=`public class Student {
    private String name;
    private int age;

    public void displayName() {
        System.out.println(name);
    }

    public int getAge() {
        return age;
    }
}
`;
editor.value=sample;lines();
function lines(){document.getElementById("numbers").innerHTML=Array.from({length:Math.max(1,editor.value.split("\n").length)},(_,i)=>i+1).join("<br>")}
editor.addEventListener("input",lines);
async function post(path,button,label){button.disabled=true;const old=button.textContent;button.textContent=label;try{const r=await fetch(path,{method:"POST",headers:{"Content-Type":"text/plain; charset=utf-8"},body:editor.value});return await r.json()}catch(e){out.textContent="Cannot contact local server. Check that the PowerShell server is still running.\n"+e.message;return null}finally{button.disabled=false;button.textContent=old}}
document.getElementById("analyze").onclick=async()=>{out.textContent="Analyzing source with JavaParser…";const r=await post("/api/analyze",document.getElementById("analyze"),"Analyzing…");if(!r)return;if(!r.success){out.textContent="Parse failed:\n"+(r.problems||[]).join("\n");return}out.textContent=r.summary+"\n\nAnalysis completed locally.";document.getElementById("types").textContent=r.types.length;document.getElementById("methods").textContent=r.methods.length;document.getElementById("findingsCount").textContent=r.findings.length;document.getElementById("structCount").textContent=r.types.length;document.getElementById("findCount").textContent=r.findings.length;
const s=document.getElementById("structure");s.replaceChildren();if(!r.types.length)s.textContent="No types found.";r.types.forEach(t=>{const d=document.createElement("div");d.className="item";const b=document.createElement("b");b.textContent=t.name+" · "+t.kind;d.append(b,document.createTextNode("Fields: "+(t.fields.join(", ")||"none")));r.methods.filter(m=>m.className===t.name).forEach(m=>{const p=document.createElement("div");p.textContent=`ƒ ${m.name}() · ${m.parameters} parameter(s) · ${m.statementCount} statement(s)`;d.append(p)});s.append(d)});
const f=document.getElementById("findingList");f.replaceChildren();if(!r.findings.length){f.className="empty";f.textContent="No configured heuristic triggered. This does not prove the code has no issues."}else r.findings.forEach(x=>{const d=document.createElement("div");d.className="item finding";const b=document.createElement("b");b.textContent=x.title+" ";const badge=document.createElement("span");badge.className="severity";badge.textContent=x.severity;b.append(badge);const p=document.createElement("p");p.textContent=x.location+" · "+x.reason;const q=document.createElement("p");q.textContent="Recommendation: "+x.recommendation;d.append(b,p,q);f.append(d)});
const su=document.getElementById("suggestions");su.replaceChildren();r.suggestions.forEach(x=>{const d=document.createElement("div");d.className="item";d.textContent="↗ "+x;su.append(d)})};
document.getElementById("compile").onclick=async()=>{out.textContent="Checking source with the JDK compiler…";const r=await post("/api/compile",document.getElementById("compile"),"Checking…");if(r)out.textContent=(r.success?"✓ COMPILE SUCCESS\n\n":"✕ COMPILE FAILED\n\n")+r.output};
document.getElementById("load").onclick=()=>{editor.value=sample;lines();out.textContent="Sample loaded. Click Analyze code or Compile check."};
document.getElementById("clear").onclick=()=>out.textContent="Output cleared.";
