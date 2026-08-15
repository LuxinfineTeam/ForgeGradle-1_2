# ForgeGradle 1.2 extended
Это форк проекта https://github.com/anatawa12/ForgeGradle-1.2/

### Список изменений:
- Удалены все тестовые модули и зависимости (JUnit)
- Удален плагин launch4j и связанный с ним код
- Удалена интеграция с Eclipse IDE (плагин и workspace константы), сохранены только Eclipse JDT библиотеки для обработки кода
- Удалена поддержка LiteLoader плагина
- Удалена поддержка CurseForge плагина (загрузка модов на CurseForge)
- Удалена функция добавления git hash в версию манифеста
- Удалена задача проверки совместимости зависимостей с Java 8
- Удалены неиспользуемые зависимости (wagon-ssh, trove4j, httpclient/httpmime)
- Подключен Dependabot для автоматического обновления зависимостей
- Поддержка запуска gradle на JDK 17-21
- Сохранение module (новое поле в IntelliJ IDEA конфигурациях) при запуске genIntellijRuns
- Использование org.glavo:pack200:0.3.0 для замены удаленного Pack200 класса из новых версий Java
- Компиляция класса GradleStart на java8 (изначально под java6 собиралось)
- Перенос таска genIntellijRuns в группу "ForgeGradle" (изначаньно таск валялся в other)
- Автоматический поиск Java8 через gradle toolchain api для запуска майнкрафт через runClient / runServer
- Добавлены проперти clientJvmArgs и serverJvmArgs в minecraft конфигурации, позволяющие настроить JVM аргументы запуска. Можно использовать для настроек по типу -Dfml.coreMods.load
- Небольшие оптимизации общей работы плагина за счет перехода на java nio в некоторых местах
- Поддержка автоматического применения _at.cfg из всех зависимостей проекта при setupDecompWorkspace. При необходимости можно отключить указав useAtFromDependencies=false в minecraft конфигурации
- Поддержка современной версии Gradle - 9.7.0
- Обновление specialsource до последней версии для поддержки сборки модов выше java8

### Пример подключения плагина:
```groovy
buildscript {
    repositories {
        maven { url 'https://jitpack.io' }
        maven {
            name 'forge'
            url 'https://maven.minecraftforge.net'
        }
    }
    dependencies {
        classpath('com.github.LuxinfineTeam.ForgeGradle-1_2:ForgeGradle:main-SNAPSHOT') {
            changing = true
        }
    }
}

apply plugin: 'forge'

[compileJava, compileTestJava]*.options*.encoding = 'UTF-8'
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

version = "1.0"
base {
    archivesName = "MyModName"
}

minecraft {
    version = "1.7.10-10.13.4.1614-1.7.10"
    replace '@VERSION@', version
    
    // Можно задать аргументы запуска, опционально
    clientJvmArgs = [
        '-Xmx2048M',
        '-Xms1024M',
    ]
    serverJvmArgs = [
        '-Xmx2048M',
        '-Xms1024M',
    ]
    
    // Если по какой-то причине вам не нужно подключение AT из зависимостей - расскоментируйте эту строку
    //useAtFromDependencies=false
}
```

### Требования
- Gradle 6.7 (С этой версии введен toolchain api)
- JDK 8 (для запуска игры в IDE) и JDK 17-21 для запуска gradle