# Linkup ბექენდის განვითარების დეტალური გეგმა (Detailed Action Plan)

ეს დოკუმენტი წარმოადგენს ბექენდის განვითარების გაფართოებულ, დეტალურ სამოქმედო გეგმას, რომელიც ეფუძნება **Social Activity App Blueprint.pdf**-ს. თითოეული ეტაპი დაყოფილია კონკრეტულ ტექნიკურ ამოცანებად (Tasks).

---

## ეტაპი 1: ინფრასტრუქტურა და ფუნდამენტური მონაცემთა ბაზა
ამ ეტაპზე ვქმნით მყარ საძირკველს პროექტისთვის, ვმართავთ სქემებს და ვაკონფიგურირებთ PostGIS-ს.

- [ ] **1.1 Docker გარემოს მოწყობა**
  - [ ] შევქმნათ `docker-compose.yml`.
  - [ ] დავამატოთ `postgis/postgis` იმიჯი PostgreSQL-სთვის.
  - [ ] დავაკონფიგურიროთ Volume-ები მონაცემთა შესანახად და პორტები (5432).
- [ ] **1.2 მონაცემთა ბაზის მიგრაციები (Flyway ან Liquibase)**
  - [ ] დავამატოთ Flyway/Liquibase-ის Dependency `pom.xml`-ში.
  - [ ] **`V1__init_schema.sql`**: შევქმნათ `Users`, `Friendships`, `Groups`, `Group_Members` ცხრილები (შესაბამისი Primary/Foreign გასაღებებით).
  - [ ] **`V2__activity_schema.sql`**: შევქმნათ `Activities`, `Locations` და `Participants` ცხრილები.
  - [ ] **`V3__postgis_indexes.sql`**: `Locations` ცხრილში დავამატოთ PostGIS GiST ინდექსები `geom_point` სვეტზე.
- [ ] **1.3 JPA Entities და მოდულური სტრუქტურის დასრულება**
  - [ ] `Identity` მოდულში `User` Entity-ის შექმნა.
  - [ ] `Social` მოდულის შექმნა და `Friendship`, `Group` Entity-ების გაწერა.
  - [ ] `Activity` მოდულში არსებული Entity-ების Hibernate Spatial (`org.locationtech.jts.geom.Point`) მხარდაჭერის ტესტირება.

---

## ეტაპი 2: იდენტიფიკაცია, უსაფრთხოება და RLS (Row-Level Security)
ვინაიდან სოციალური აპლიკაციაა, მონაცემთა იზოლაცია უმნიშვნელოვანესია. ვნერგავთ Stateless JWT-ს და ბაზის დონეზე დაცვას.

- [ ] **2.1 Stateless Authentication (JWT)**
  - [ ] `spring-boot-starter-security` და `jjwt` (ან მსგავსი) ბიბლიოთეკების დამატება.
  - [ ] `SecurityFilterChain` კონფიგურაცია, რათა გაითიშოს Session (`SessionCreationPolicy.STATELESS`).
  - [ ] `JwtAuthenticationFilter`-ის შექმნა, რომელიც მოთხოვნის Header-იდან `Bearer` ტოკენს ამოიღებს, შეამოწმებს და ავტორიზაციას მიანიჭებს.
  - [ ] `AuthController`-ის შექმნა `/api/v1/auth/login` და `/register` ენდფოინთებით.
- [ ] **2.2 Row-Level Security (RLS) პოლიტიკები PostgreSQL-ში**
  - [ ] მიგრაციის ფაილის (`V4__rls_policies.sql`) შექმნა.
  - [ ] ცხრილებზე RLS-ის ჩართვა: `ALTER TABLE activities ENABLE ROW LEVEL SECURITY;`.
  - [ ] პოლიტიკის დაწერა, მაგ.: მომხმარებელს შეუძლია ნახოს Activity თუ ის არის `creator_id`, ან თუ `visibility = 'PUBLIC'`, ან თუ ის არის Activity-ის მონაწილე/მეგობარი.
- [ ] **2.3 Data Source Connection Interceptor**
  - [ ] Spring Boot-ში DataSource-ის proxy/interceptor-ის დაწერა.
  - [ ] ყოველი ტრანზაქციის დაწყებისას გავუშვათ ქუერი: `SET LOCAL app.current_user_id = :userId`, რათა RLS პოლიტიკამ იცოდეს ვინ კითხულობს მონაცემებს.

---

## ეტაპი 3: Frictionless Activity Creation & CQRS პატერნი
აქ ვახდენთ ბუნებრივი ენის (NLP) ინტეგრაციას და ვაცალკევებთ ჩაწერის (Command) და წაკითხვის (Query) ლოგიკას.

- [ ] **3.1 NLP (Hawking Time Parser) ინტეგრაცია**
  - [ ] Zoho Hawking-ის dependency-ს დამატება (ან ექვივალენტის, Blueprint-ის მიხედვით).
  - [ ] `NlpParserService`-ის დაწერა, რომელიც მიიღებს სტრინგს (მაგ. "Let's grab coffee tomorrow at 5pm") და დააბრუნებს Parse-რებულ ობიექტს (Title, Start Time, Location).
- [ ] **3.2 Factory Method პატერნი**
  - [ ] `ActivityFactory`-ის შექმნა `activity` მოდულში.
  - [ ] ლოგიკის დაწერა, რომელიც ამოიცნობს, მოსული მონაცემები `CasualPlan`-ია (დროის/ადგილის გარეშე) თუ `StructuredEvent` (კონკრეტული დრო/ლოკაცია) და შექმნის შესაბამის Entity-ს.
- [ ] **3.3 CQRS-ის იმპლემენტაცია**
  - [ ] **Command Side**: `CreateActivityCommand` DTO-სა და `ActivityCommandHandler`-ის შექმნა, რომელიც ბაზაში წერს და ველიდაციას აკეთებს.
  - [ ] **Query Side**: `ActivityQueryRepository`-ის შექმნა (შესაძლოა JdbcTemplate-ით ან Spring Data Projections-ით), რომელიც პირდაპირ კითხულობს ოპტიმიზებულ მონაცემებს (ან Materialized View-დან) და აბრუნებს Frontend-ისთვის გამზადებულ JSON ფორმატს.

---

## ეტაპი 4: Social Graph და ასინქრონული ივენთები (Observer Pattern)
სისტემის სხვადასხვა ნაწილებს შორის (Activity-ის შექმნა -> ნოტიფიკაცია) მყარი, მაგრამ ერთმანეთისგან დამოუკიდებელი კომუნიკაციის აწყობა.

- [ ] **4.1 Social Graph API**
  - [ ] `/api/v1/friends/request`, `/accept`, `/decline` ენდფოინთები.
  - [ ] მეგობრობის დადასტურებისას ივენთის გასროლა (`FriendshipAcceptedEvent`).
- [ ] **4.2 Transaction-Bound Event Publishing**
  - [ ] როცა ახალი Activity იქმნება, ვრთავთ `ApplicationEventPublisher.publishEvent(new ActivityCreatedEvent(...))`.
  - [ ] Notification მოდულში ივენთის დაჭერა ექსკლუზიურად `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` ანოტაციით. ეს უზრუნველყოფს, რომ Push ნოტიფიკაცია არ გაიგზავნოს, თუ ბაზაში Activity-ის შენახვის ტრანზაქცია გაუქმდა (Rollback).

---

## ეტაპი 5: Real-Time Feed, Geospatial Discovery და Caching
ეს ეტაპი ფოკუსირებულია წარმადობაზე, სკალირებასა და რუკის/Feed-ის ცოცხალ განახლებაზე.

- [ ] **5.1 PostGIS ST_ClusterDBSCAN (Clustering)**
  - [ ] რუკის ენდფოინთის (`/api/v1/activities/map`) გაკეთება.
  - [ ] Native SQL Query-ის დაწერა `ST_ClusterDBSCAN` გამოყენებით, რათა ახლოს მყოფი Activity-ები დაჯგუფდეს ერთ მარკერად და არ გადაიტვირთოს მობილურის მეხსიერება.
- [ ] **5.2 Redis და Hybrid Fan-Out სტრატეგია**
  - [ ] Redis-ის ინტეგრაცია (Spring Data Redis).
  - [ ] ჩვეულებრივი მომხმარებლისთვის: **Fan-Out on Write** (Push Model). როცა ქმნის Activity-ს, Redis-ის მეგობრების სიებში პირდაპირ ემატება ამ Activity-ის ID (Timeline/Feed სწრაფი წაკითხვისთვის).
  - [ ] 'Influencer'-ებისთვის (ბევრი გამომწერი): **Fan-Out on Read** (Pull Model). მათი პოსტები იკითხება დინამიურად, რათა არ მოხდეს Write Amplification (ბაზის გადატვირთვა ათასობით ჩანაწერით).
- [ ] **5.3 Notification Transport (FCM & SSE)**
  - [ ] **Firebase Cloud Messaging (FCM)**-ის სერვერის კონფიგურაცია, აპლიკაციის ფონურ რეჟიმში (Background) ნოტიფიკაციებისთვის.
  - [ ] **Server-Sent Events (SSE)** ენდფოინთის (`/api/v1/feed/stream`) შექმნა, რათა აპლიკაციამ წინა პლანზე (Foreground) ყოფნისას ცოცხლად და მსუბუქად მიიღოს ინფორმაცია (მაგ. მეგობარი შემოუერთდა ივენთს) WebSockets-ის მძიმე ინფრასტრუქტურის გარეშე.

---

## ეტაპი 6: დასკვნითი პოლირება, ტესტირება და დეპლოიმენტის მზადყოფნა
- [ ] **6.1 ინტეგრაციული ტესტირება Testcontainers-ით**
  - [ ] ტესტების დაწერა, რომელიც რეალურად აწევს PostgreSQL-სა და Redis-ს კონტეინერებს ტესტების გაშვებისას.
- [ ] **6.2 Modulith-ის ვალიდაცია**
  - [ ] ArchUnit / Spring Modulith ვალიდაციის გაფართოება ახალ მოდულებზე (`social`, `auth`, `notification`).
- [ ] **6.3 ობსერვაბილურობა (Observability)**
  - [ ] OpenTelemetry-ის, Micrometer-ის და Prometheus ენდფოინთების (`/actuator/prometheus`) ჩართვა.
  - [ ] ლოგებში Trace ID-ების დამატება.
- [ ] **6.4 CI/CD მზადყოფნა**
  - [ ] GitHub Actions / GitLab CI ფაილის მომზადება ავტომატური ბილდისთვის და ტესტებისთვის.
