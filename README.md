# Durga

Application de suivi d'investigations client.

## Contexte et objectif

Durga sert à suivre des tâches d'investigation client (bugs, demandes,
incidents) : sujet, description, itération, serveur concerné, statut
d'avancement, et éventuel blocage (« en attente de »).

Le projet est volontairement construit en couches simples et indépendantes,
sans base de données ni ORM, pour rester facile à faire évoluer. Les
opérations métier sont exposées via l'API REST et via **MCP (Model Context
Protocol)** pour permettre leur utilisation par des agents IA.

## Stack technique

- Java 21
- Spring Boot 3 (module Gradle : `id 'org.springframework.boot' version '4.1.1'`)
- Gradle (wrapper fourni : `gradlew` / `gradlew.bat`)
- Lombok
- Jackson (+ `jackson-datatype-jsr310` pour les `LocalDateTime`)
- Spring AI MCP Server WebMVC 2.0.1, basé sur le SDK MCP Java officiel
- Stockage : fichier JSON local (`data/tasks.json`), **aucun ORM, aucune base
  de données**
- Frontend : SPA HTML/CSS/JS **sans framework**, servie en statique par Spring
  Boot (`src/main/resources/static`)

## Architecture

```
src/main/java/nf/durga/
├── model/       Task, Status, WaitingFor, TaskSource (enums + POJO Lombok)
├── storage/     JsonTaskStorage (lecture/écriture data/tasks.json via Jackson)
├── service/     TaskService, UpdateTaskRequest (logique métier)
├── web/         TaskController (REST), ApiExceptionHandler (mapping erreurs)
└── mcp/         TaskMcpTools, McpConfig (adaptation et exposition MCP)

src/main/resources/
├── static/      index.html, app.js, style.css (SPA)
└── application.properties
```

Règles de séparation à respecter lors des évolutions :

- **`model`** : uniquement des structures de données (pas de logique).
- **`storage`** (`JsonTaskStorage`) : lecture/écriture du fichier JSON
  uniquement. Pas de logique métier, pas d'annotation Spring liée au métier
  (seul `@Component` est présent, pour le câblage/injection).
  Expose `load()` et `save(List<Task>)`.
- **`service`** (`TaskService`) : contient toute la logique métier
  (génération d'UUID, gestion de `createdAt`/`updatedAt`, règles de
  validation à venir). Appelle `storage.load()` au démarrage
  (`@PostConstruct`) et `storage.save()` après chaque modification.
- **`web`** (`TaskController`) : ne contient **aucune logique métier**,
  délègue uniquement à `TaskService`. `ApiExceptionHandler` traduit les
  exceptions métier (ex. `NoSuchElementException`) en réponses HTTP.
- **`mcp`** (`TaskMcpTools`) : adapte les paramètres MCP puis délègue
  exclusivement à `TaskService`. Les outils MCP ne contiennent aucune logique
  métier et n'appellent jamais directement le stockage.

```text
SPA -> REST ----\
                 -> TaskService -> JsonTaskStorage
Agent -> MCP ---/
```

## Modèle de données (`Task`)

| Champ              | Type            | Notes                                   |
|---------------------|-----------------|------------------------------------------|
| id                  | UUID            | généré automatiquement à la création      |
| subject             | String          |                                            |
| description         | String          |                                            |
| iteration           | Integer         |                                            |
| serverKey           | String          |                                            |
| status              | Status          | DATA_GRABBING, INVESTIGATION, FIXING, REPORT |
| waitingFor          | WaitingFor      | NONE, CLOUDOPS, RND, CUSTOM                |
| waitingForCustom    | String          | libellé libre si `waitingFor = CUSTOM`     |
| source              | TaskSource      | MANUAL, AGENT, COPILOT, EMAIL              |
| createdAt/updatedAt | LocalDateTime   | gérés automatiquement par `TaskService`    |
| archived            | Boolean         | `false` par défaut ; les tâches archivées n'apparaissent plus dans `list()`/la vue principale |
| dueDate             | LocalDate       |                                            |
| tags                | String          |                                            |
| priority            | Integer         | 1 = critique, 2 = élevée, 3 = normale, 4 = faible |
| jiraUrl             | String          | lien vers la demande Jira                    |
| requires            | List<UUID>      | tâches préalables à terminer avant celle-ci  |
| sortOrder           | Double          | rang d'affichage pour le glisser-déposer (sans lien avec `priority`), renumboté par `TaskService.reorder()` |

Logique métier (`TaskService`) exposée en plus du CRUD de base :

- `search(serverKey, status, waitingFor, archived, priority)` : recherche à
  critères tous optionnels (les `null` sont ignorés).
- `archive(id)` : marque une tâche comme archivée.
- `changeStatus(id, newStatus)` : change uniquement le statut.
- `patch(id, request)` : met à jour uniquement les champs non nuls de
  `UpdateTaskRequest` et conserve tous les autres champs.
- `getActiveTasks()` : tâches non archivées (équivalent à `list()`).
- `getBlockedTasks()` : tâches actives avec `waitingFor != NONE`.
- `getTasksByServer(serverKey)` : tâches actives d'un serveur donné.

## API REST

Base : `/api/tasks`

| Méthode | URL              | Description                     |
|---------|------------------|----------------------------------|
| GET     | /api/tasks       | Liste les tâches non archivées   |
| GET     | /api/tasks/search | Recherche filtrée (`?serverKey=&status=&waitingFor=`, tous optionnels) |
| GET     | /api/tasks/blocked | Tâches actives en attente d'un tiers (`archived=false` et `waitingFor != NONE`) |
| GET     | /api/tasks/server/{serverKey} | Tâches actives d'un serveur donné |
| GET     | /api/tasks/{id}  | Détail d'une tâche (404 si absente) |
| POST    | /api/tasks       | Création (201)                   |
| PUT     | /api/tasks/{id}  | Mise à jour (404 si absente)      |
| DELETE  | /api/tasks/{id}  | Suppression (204, 404 si absente) |
| POST    | /api/tasks/{id}/archive | Archive la tâche (200 si succès, 404 si absente) |
| POST    | /api/tasks/{id}/status | Change uniquement le statut, body `{"status": "FIXING"}` (200 si succès, 404 si absente) |
| POST    | /api/tasks/{id}/reorder | Déplace la tâche à un rang donné, body `{"sortOrder": 2.5}` ; recalcule et renvoie toutes les tâches actives renumbotées (200 si succès, 404 si absente) |

Ces endpoints ne font que déléguer à `TaskService` (aucune logique métier dans
`TaskController`).

## Réunions client

Les réunions client sont stockées séparément dans `data/meetings.json`. Une
réunion contient un identifiant, une clé serveur, une date/heure
(`meetingAt`) et une liste d'identifiants de tâches (`taskIds`), ainsi que
ses dates de création et de mise à jour.

API REST, base `/api/meetings` :

| Méthode | URL | Description |
|---------|-----|-------------|
| GET | /api/meetings | Liste les réunions |
| GET | /api/meetings/{id} | Détail d'une réunion |
| POST | /api/meetings | Création (201) |
| PUT | /api/meetings/{id} | Mise à jour |
| DELETE | /api/meetings/{id} | Suppression |

Les outils MCP correspondants sont `create_client_meeting`,
`list_client_meetings`, `get_client_meeting`, `update_client_meeting` et
`delete_client_meeting`. Les dates sont transmises au format ISO-8601 et les
tâches sont référencées par UUID ; leurs données ne sont pas dupliquées dans
la réunion.

## MCP

Le serveur MCP démarre avec l'application Spring Boot et utilise le transport
**Streamable HTTP** sur :

```text
http://localhost:8080/mcp
```

La configuration se trouve dans `application.properties`. Gradle importe le
BOM `org.springframework.ai:spring-ai-bom:2.0.1` et utilise
`org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, qui intègre le
SDK MCP Java officiel.

Outils exposés :

| Outil | Délégation métier |
|-------|--------------------|
| `create_task` | construit l'entrée avec `source=COPILOT`, puis `TaskService.create()` |
| `list_tasks` | `TaskService.getActiveTasks()` |
| `search_tasks` | `TaskService.search()` |
| `update_task` | construit `UpdateTaskRequest`, puis `TaskService.patch()` |
| `archive_task` | `TaskService.archive()` |
| `change_status` | `TaskService.changeStatus()` |
| `get_blocked_tasks` | `TaskService.getBlockedTasks()` |
| `get_tasks_by_server` | `TaskService.getTasksByServer()` |

`update_task` est un patch : seuls les champs non nuls sont modifiés. Une
valeur `null` signifie « ne pas modifier ». Les erreurs existantes
(identifiant absent, etc.) remontent au client MCP ; les UUID, statuts,
attentes et dates invalides produisent des messages explicites.

Exemple de configuration d'un client/agent compatible MCP Streamable HTTP :

```json
{
  "mcpServers": {
    "durga": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

L'agent peut ensuite appeler, par exemple, `get_blocked_tasks`, puis
`change_status` avec l'UUID choisi. Le serveur MCP HTTP n'ajoute pas
d'authentification par défaut : ne pas exposer `/mcp` publiquement sans
contrôle d'accès en amont.

## Frontend (SPA)

`src/main/resources/static/` : `index.html` + `app.js` + `style.css`, sans
framework JS, utilise `fetch()`. La SPA affiche les tâches actives sous forme
de lignes empilées façon todo-list (`GET /api/tasks`). Un clic
sur une ligne ouvre une modale contenant l'ensemble des champs de la tâche,
dont le lien vers la demande Jira lorsqu'il est renseigné ;
la modale se ferme avec son bouton, un clic sur l'arrière-plan ou la touche
`Escape`.

Une barre latérale permet également de filtrer les tâches actives par statut
et par serveur. Les filtres sont appliqués côté navigateur aux tâches déjà
chargées et pourront être complétés ultérieurement par d'autres critères.
La modale permet aussi de modifier les champs éditables d'une tâche puis de
valider les changements via l'API REST. Un calendrier mensuel est affiché
au-dessus des filtres : il charge les réunions client, permet de naviguer
entre les mois et colore chaque réunion selon son serveur. Le bouton
« Ajouter » ouvre un formulaire de création de réunion. Un clic sur une
réunion du calendrier ouvre la même modale pré-remplie, permettant de la
modifier ou de la supprimer (avec confirmation).

Les champs faisant référence à d'autres tâches (« Tâches préalables » sur une
tâche, tâches associées à une réunion) sont présentés et édités via un
sélecteur dédié : liste à cocher affichant le sujet des tâches (jamais leur
UUID), filtrable par serveur, qui exclut systématiquement les tâches
archivées ainsi que, pour « Tâches préalables », la tâche en cours d'édition
elle-même.

Les lignes peuvent être réorganisées par glisser-déposer (drag & drop HTML5
natif, sans framework). Au dépôt, la tâche déplacée reçoit un rang
(`sortOrder`) intermédiaire calculé à partir de ses nouvelles voisines
affichées, ce qui met à jour l'affichage immédiatement. L'écran est ensuite
bloqué par un calque plein écran le temps de l'appel à
`POST /api/tasks/{id}/reorder` : le backend renumbote alors l'ensemble des
tâches actives avec des rangs entiers croissants, évitant toute dérive
numérique, puis la liste est rechargée et l'écran débloqué. Le champ
`sortOrder` ne porte aucune sémantique métier (contrairement à `priority`) :
il ne sert qu'à l'ordre d'affichage.

## Stockage

Fichier `data/tasks.json` (créé automatiquement au premier `save()`, dossier
`data/` non versionné). Pas de migration/versionnage de schéma à ce stade.

## Lancer le projet

```powershell
.\gradlew.bat bootRun
```

Application disponible sur `http://localhost:8080/` (SPA) et
`http://localhost:8080/api/tasks` (API).

## Tests / build

```powershell
.\gradlew.bat compileJava
.\gradlew.bat test
```

## Évolutions futures envisagées

- Validation des entrées (`spring-boot-starter-validation` déjà présent en
  dépendance).
- Authentification/autorisation devant l'endpoint MCP avant toute exposition
  hors environnement local.
- Éventuelle suppression depuis la SPA.
