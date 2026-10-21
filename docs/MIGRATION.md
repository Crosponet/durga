# Migration initiale des Tasks et initialisation Git (Windows)

Operation manuelle unique, hors de l'application. Le script
[`Migrate-Tasks.ps1`](../scripts/Migrate-Tasks.ps1) convertit les documents ;
il ne lance pas Git, ne publie rien et ne modifie pas la configuration de Durga.

## 1. Preparer la bascule et les chemins

Arreter Durga jusqu'a la fin du push. Utiliser **PowerShell 7.5 ou plus recent**
(`pwsh`) et Git disponible dans le PATH. La conversion conserve les dates sous
forme de chaines, sans conversion implicite de fuseau horaire.

Definir les variables suivantes dans un meme terminal, puis les reutiliser
dans toutes les etapes :

```powershell
$ErrorActionPreference = 'Stop'
$app = 'C:\dev\external\durga'
$repo = 'C:\dev\analysis\_DATAS\durga_tasks'
$source = Join-Path $app 'data\tasks.json'
$orderFile = Join-Path $app 'data\task-order.json'
$script = Join-Path $app 'scripts\Migrate-Tasks.ps1'
$gitName = 'Votre nom'
$gitEmail = 'vous@example.com'
$remoteUrl = '<URL SSH ou HTTPS du projet GitLab vide>'
$branch = 'main'
```

Adapter ces valeurs a l'installation. Les chemins du script sont tous des
parametres obligatoires : aucune destination n'est codee en dur.

Le depot `$repo` doit etre vide ou absent. Il contiendra uniquement Git et
`tasks\<uuid>.json`. La source, l'ordre local, `data\meetings.json` et les
logs restent hors de ce depot, dans l'installation `$app`. Les chemins
parametres ne doivent pas traverser de liens symboliques ou de jonctions vers
le depot partage.

Creer manuellement un projet GitLab **vide**, sans README, licence ni
`.gitignore` initial. L'authentification reste celle de Git. Si le remote a
deja un historique, ne pas suivre l'initialisation ci-dessous : cloner ce
remote et preparer une migration adaptee, sans fusion automatique ni push force.

Sauvegarder normalement `data` et les logs avant la bascule. Le script conserve
en plus une copie exacte de la source a cote de celle-ci, suffixee
`.before-git-<identifiant>.bak`.

## 2. Convertir les Tasks

```powershell
& $script -SourceFile $source -RepositoryPath $repo -OrderFile $orderFile
```

La conversion preserve les UUID, les champs metier, les dates, le Markdown,
les Tasks archivees et les references `requires`. Elle exclut `sortOrder` et
`revision` des documents partages, et extrait l'ordre en une liste d'UUID
dans `$orderFile`. Un ancien `workStatus` absent prend la valeur `TODO`.

Le script valide tous les documents avant de commencer les ecritures et refuse
les UUID dupliques, un depot cible non vide ou un ordre local deja present.
La source n'est ni modifiee ni supprimee.

En cas d'echec, inspecter les fichiers deja ecrits et la sauvegarde avant toute
nouvelle tentative. Ne supprimer que les fichiers identifies comme issus de
cette tentative, jamais un repertoire contenant d'autres donnees.

## 3. Initialiser le depot et verifier les fichiers

```powershell
function Invoke-TaskGit {
    & git -C $repo @args
    if ($LASTEXITCODE -ne 0) { throw "Echec Git : $args" }
}
Invoke-TaskGit init "--initial-branch=$branch"
Invoke-TaskGit config user.name $gitName
Invoke-TaskGit config user.email $gitEmail
Invoke-TaskGit add -- tasks
Invoke-TaskGit diff --cached --stat
```

Verifier que seuls les documents `tasks\<uuid>.json` sont listes. Ni l'ordre
local, ni les reunions, ni les logs, ni la source historique ne doivent etre
committes.

## 4. Publier le premier checkpoint

```powershell
Invoke-TaskGit commit -m 'tasks: migrate legacy documents'
Invoke-TaskGit remote add origin $remoteUrl
Invoke-TaskGit push --set-upstream origin $branch
Invoke-TaskGit fetch origin
Invoke-TaskGit status --short
Invoke-TaskGit rev-parse HEAD
Invoke-TaskGit rev-parse '@{upstream}'
```

`status --short` doit etre vide ; les deux revisions doivent etre identiques.
Le helper arrete la procedure au premier echec Git. Si le push echoue,
conserver les fichiers et corriger l'acces au remote, sans refaire la conversion,
sans reinitialiser le depot et sans push force.

Le premier commit est un checkpoint de migration manuelle, pas une action
metier effectuee par Durga : ne pas fabriquer d'entrees d'audit pour cet import.
Les logs historiques restent locaux.

## 5. Configurer et verifier Durga

```powershell
Set-Location -LiteralPath $app
$env:DURGA_TASKS_REPOSITORY = $repo
.\gradlew.bat bootRun
```

La variable vaut pour ce terminal et ses processus enfants. Pour la conserver
au niveau utilisateur :

```powershell
[Environment]::SetEnvironmentVariable('DURGA_TASKS_REPOSITORY', $repo, 'User')
```

Rouvrir les terminaux et l'IDE, ou definir la variable dans la configuration
de lancement IDE. Conserver `$app` comme repertoire de travail pour retrouver
les reunions, l'ordre et les logs existants.

Verifier les Tasks actives **et archivees**, UUID, Markdown, prerequis et dates,
puis l'ordre local et les reunions. Une reorganisation doit laisser
`git status --short` vide et ne creer aucun commit. Une modification metier doit
creer au plus un commit et etre visible depuis un autre clone apres
actualisation. Conserver la source et sa sauvegarde jusqu'a validation.

Les autres utilisateurs clonent le meme remote dans leur propre checkout et
configurent leur identite et `DURGA_TASKS_REPOSITORY`. Ne pas leur copier
`task-order.json`, `meetings.json` ou les logs.
