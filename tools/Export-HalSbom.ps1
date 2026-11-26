param([string]$Inventory = '', [string]$GradleUserHome = '', [switch]$CheckAdvisories)
$ErrorActionPreference='Stop'
$root = Split-Path -Parent $PSScriptRoot
if(-not $Inventory){$Inventory=Join-Path $root 'build/dependency-review/dependency-graph.json'}
$Inventory=(Resolve-Path -LiteralPath $Inventory).Path
$outputDirectory=Split-Path -Parent $Inventory
$graph=Get-Content -LiteralPath $Inventory -Raw|ConvertFrom-Json
if(-not $GradleUserHome){$GradleUserHome=Join-Path $root 'build/tooling/gradle-home'}
$cache=Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
$applicationVersion=[regex]::Match([IO.File]::ReadAllText((Join-Path $root 'app/build.gradle.kts')), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$memo=@{}
function Read-License($group,$name,$version,$depth=0){
 $key="${group}:${name}:${version}"
 if($memo.ContainsKey($key)){return $memo[$key]}
 if($depth -gt 10 -or $version -match '\$\{'){return @{status='unresolved-parent';source=$key;licenses=@()}}
 $dir=Join-Path $cache (Join-Path $group (Join-Path $name $version))
 $poms=@(Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.pom' -ErrorAction SilentlyContinue)
 if(-not $poms.Count){$r=@{status='pom-not-cached';source=$key;licenses=@()};$memo[$key]=$r;return $r}
 [xml]$pom=Get-Content $poms[0].FullName
 $ls=@($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="licenses"]/*[local-name()="license"]')|ForEach-Object{@{name=[string]$_.name;url=[string]$_.url}})
 if($ls.Count){$r=@{status='declared';source=$key;licenses=$ls}}
 elseif($pom.project.parent){$p=$pom.project.parent;$r=Read-License ([string]$p.groupId) ([string]$p.artifactId) ([string]$p.version) ($depth+1)}
 else{$r=@{status='no-declaration';source=$key;licenses=@()}}
 $memo[$key]=$r;return $r
}
$all=@{}
foreach($scope in @('runtime','buildTools')){
 foreach($n in $graph.$scope.nodes){
  $ref="pkg:maven/$($n.group)/$($n.name)@$($n.version)"
  if(-not $all.ContainsKey($ref)){$all[$ref]=@{type='library';group=$n.group;name=$n.name;version=$n.version;'bom-ref'=$ref;purl=$ref;properties=@();hashes=@()}}
  $all[$ref].properties+=@{name='hal:scope';value=$scope}
 }
 foreach($a in $graph.$scope.artifacts){$ref="pkg:maven/$($a.group)/$($a.name)@$($a.version)";$all[$ref].hashes+=@{alg='SHA-256';content=$a.sha256}}
}
$licenseRows=@()
foreach($c in $all.Values){
 $lr=Read-License $c.group $c.name $c.version
 $scope=@($c.properties|ForEach-Object value)
 $licenseRows+=@{coordinate="$($c.group):$($c.name):$($c.version)";scopes=$scope;source=$lr.source;status=$lr.status;licenses=$lr.licenses;packagedArtifact=($c.hashes.Count -gt 0)}
 if($lr.licenses.Count){$c.licenses=@($lr.licenses|ForEach-Object{$l=@{name=$_.name};if($_.url -match '^https?://'){$l.url=$_.url};@{license=$l}})}
 if(-not $c.hashes.Count){$c.Remove('hashes')}else{$c.hashes=@($c.hashes|Sort-Object -Property content -Unique)}
}
$deps=@{}
foreach($scope in @('runtime','buildTools')){foreach($n in $graph.$scope.nodes){$ref="pkg:maven/$($n.group)/$($n.name)@$($n.version)";if(-not $deps.ContainsKey($ref)){$deps[$ref]=@()};$deps[$ref]+=@($n.dependsOn)}}
$bom=@{bomFormat='CycloneDX';specVersion='1.6';serialNumber=('urn:uuid:'+ [guid]::NewGuid());version=1;metadata=@{timestamp=[DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ',[Globalization.CultureInfo]::InvariantCulture);component=@{type='application';name='HAL';version='1.2.0';'bom-ref'='hal'};properties=@(@{name='hal:scope';value='Resolved release runtime and root buildscript classpath modules, including BOM nodes and dependency edges. Artifact hashes and parent-inherited POM license declarations. Excludes Gradle/JDK/SDK executables and embedded native/shaded provenance; not a legal or CVE-free certification.'})};components=@($all.Values|Sort-Object purl);dependencies=@($deps.GetEnumerator()|ForEach-Object{@{ref=$_.Key;dependsOn=@($_.Value|Sort-Object -Unique)}})}
$bom.metadata.component.version=$applicationVersion
$bom.dependencies+=@{ref='hal';dependsOn=@($graph.runtime.roots)}
$bom|ConvertTo-Json -Depth 16|Set-Content (Join-Path $outputDirectory 'hal-review.cdx.json')
$licenseRows|ConvertTo-Json -Depth 8|Set-Content (Join-Path $outputDirectory 'license-declarations.json')
"SBOM modules=$($all.Count); dependency rows=$($deps.Count)"
$licenseRows|Where-Object {$_.packagedArtifact -and -not $_.licenses.Count}|ForEach-Object {"License declaration gap: $($_.coordinate) [$($_.scopes -join ',')] $($_.status)"}
"Runtime artifacts missing inherited declaration=$(@($licenseRows|Where-Object {$_.scopes -contains 'runtime' -and $_.packagedArtifact -and -not $_.licenses.Count}).Count)"

if($CheckAdvisories){
 # Only public Maven coordinates/versions are submitted. No source, file paths or device data.
 $modules=@(@($graph.runtime.nodes)+@($graph.buildTools.nodes)|Sort-Object -Property group,name,version -Unique)
 $queries=@($modules|ForEach-Object{@{package=@{ecosystem='Maven';name=($_.group+':'+$_.name)};version=$_.version}})
 $response=Invoke-RestMethod 'https://api.osv.dev/v1/querybatch' -Method Post -ContentType 'application/json' -Body (@{queries=$queries}|ConvertTo-Json -Depth 6) -TimeoutSec 45
 if($response.results.Count -ne $queries.Count){throw 'OSV returned an incomplete result set'}
 $findings=for($i=0;$i -lt $queries.Count;$i++){
  if($response.results[$i].next_page_token){throw 'OSV pagination requires continuation; scan is incomplete'}
  foreach($v in $response.results[$i].vulns){@{package=$queries[$i].package.name;version=$queries[$i].version;id=$v.id}}
 }
 @{checkedUtc=[DateTime]::UtcNow.ToString('o');modules=$queries.Count;findings=@($findings);limits='Known OSV matches only; excludes embedded/shaded/native provenance and Gradle/JDK/SDK executables.'}|ConvertTo-Json -Depth 6|Set-Content (Join-Path $outputDirectory 'osv-result.json')
 "OSV modules=$($queries.Count); advisory matches=$(@($findings).Count)"
}
