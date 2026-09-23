# Durga

Application de suivi d'investigations client.

## Contexte et objectif

Durga sert à suivre des tâches d'investigation client (bugs, demandes,
incidents) : sujet, description, itération, serveur concerné, statut
d'avancement, et éventuel blocage (« en attente de »).

Le projet est volontairement construit en couches simples et indépendantes,
sans base de données ni ORM, pour rester facile à faire évoluer. Une
évolution prévue à terme est d'exposer l'application via **MCP (Model
Context Protocol)**, en plus (ou à la place) de la couche REST actuelle.

## Stack technique

- Java 21
- Spring Boot 3 (module Gradle : `id 'org.springframework.boot' version '4.1.1'`)
- Gradle (wrapper fourni : `gradlew` / `gradlew.bat`)
- Lombok
- Jackson (+ `jackson-datatype-jsr310` pour les `LocalDateTime`)
- Stockage : fichier JSON local (`data/tasks.json`), **aucun ORM, aucune base
  de données**
- Frontend : SPA HTML/CSS/JS **sans framework**, servie en statique par Spring
  Boot (`src/main/resources/static`)

## Architecture

```
src/main/java/nf/durga/
├── model/       Task, Status, WaitingFor, TaskSource (enums + POJO Lombok)
├── storage/     JsonTaskStorage (lecture/écriture data/tasks.json via Jackson)
├── service/     TaskService (logique métier : CRUD, UUID, timestamps)
└── web/         TaskController (REST), ApiExceptionHandler (mapping erreurs)

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

## API REST

Base : `/api/tasks`

| Méthode | URL              | Description                     |
|---------|------------------|----------------------------------|
| GET     | /api/tasks       | Liste toutes les tâches          |
| GET     | /api/tasks/{id}  | Détail d'une tâche (404 si absente) |
| POST    | /api/tasks       | Création (201)                   |
| PUT     | /api/tasks/{id}  | Mise à jour (404 si absente)      |
| DELETE  | /api/tasks/{id}  | Suppression (204, 404 si absente) |

## Frontend (SPA)

`src/main/resources/static/` : `index.html` + `app.js` + `style.css`, sans
framework JS, utilise `fetch()`. Affiche actuellement la liste des tâches
sous forme de cartes (lecture seule, `GET /api/tasks`).

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

- Exposition via MCP (Model Context Protocol).
- Validation des entrées (`spring-boot-starter-validation` déjà présent en
  dépendance).
- Éventuelle édition/suppression depuis la SPA (actuellement lecture seule).
