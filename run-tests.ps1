param([switch]FixApiUrl)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force evidence | Out-Null
if (!(Test-Path upstream)) {
 git clone https://github.com/dotnet-architecture/eShopOnWeb.git upstream
 if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
 git -C upstream checkout 4da8212117e87d808d4bbc7da6286fd2147ce606
 if ($LASTEXITCODE -ne 0) { throw 'git checkout failed' }
}
# РўРѕР»СЊРєРѕ Р°РґР°РїС‚Р°С†РёСЏ РІС‹Р±РѕСЂР° СѓСЃС‚Р°РЅРѕРІР»РµРЅРЅРѕРіРѕ SDK; target framework РѕСЃС‚Р°РµС‚СЃСЏ net8.0.
Set-Content upstream/global.json '{"sdk":{"version":"9.0.320","rollForward":"latestFeature"}}'
foreach($project in @('Web','PublicApi')) {
 dotnet build "upstream/src/$project/$project.csproj" --nologo
 if ($LASTEXITCODE -ne 0) { throw "Build failed: $project" }
}
$env:ASPNETCORE_ENVIRONMENT='Development'
$env:UseOnlyInMemoryDatabase='true'
if($FixApiUrl){$env:baseUrls__apiBase='http://localhost:5181/api/'}
$web=$null; $api=$null
try {
 $env:ASPNETCORE_URLS='http://localhost:5180'
 $web=Start-Process dotnet -ArgumentList 'run --no-build --no-launch-profile --project upstream/src/Web/Web.csproj' -PassThru -WindowStyle Hidden -RedirectStandardOutput evidence/web.log -RedirectStandardError evidence/web-error.log
 $env:ASPNETCORE_URLS='http://localhost:5181'
 $api=Start-Process dotnet -ArgumentList 'run --no-build --no-launch-profile --project upstream/src/PublicApi/PublicApi.csproj' -PassThru -WindowStyle Hidden -RedirectStandardOutput evidence/api.log -RedirectStandardError evidence/api-error.log
 foreach($url in @('http://localhost:5180/','http://localhost:5181/api/catalog-items')) {
  $ready=$false
  for($i=0;$i -lt 60;$i++){try{Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 3 | Out-Null; $ready=$true;break}catch{Start-Sleep -Seconds 1}}
  if(!$ready){throw "Startup timeout: $url"}
 }
 mvn test "-Dshop.external=true"
 $result=$LASTEXITCODE
 Copy-Item target/surefire-reports/* evidence -Force
} finally {
 foreach($process in @($web,$api)){if($null -ne $process){taskkill /PID $process.Id /T /F | Out-Null}}
 Remove-Item Env:baseUrls__apiBase -ErrorAction SilentlyContinue
}
exit $result
