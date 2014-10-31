Setup Development Environment for Mac OSX
=========================================

## Install Homebrew

Install the [Homebrew](http://brew.sh) package manager for OS X.

## Install & Configure P4 (Perforce Command-Line Client)

Download from <http://www.perforce.com/downloads/Perforce/Customer>.

Perforce Clients -> P4: Command-line client ->
Macintosh -> Mac OS X 10.5 for x86_64

````
$ sudo mkdir -p /usr/local/bin
$ sudo mv p4 /usr/local/bin
$ chmod +x /usr/local/bin/p4
````

You may also want to download and install P4V, a visual Perforce client.

More information is available at <http://www.perforce.com/perforce/doc.current/manuals/p4guide/01_install.html#1070774>

````
$ vi ~/.bash_profile
````

Add the following contents:

````
export P4PORT=localhost:1066
export P4USER={your p4 username}
export P4CONFIG=.p4config
export P4EDITOR=/usr/bin/vi
export PATH=$PATH:/opt/zimbra/bin
export ZIMBRA_HOSTNAME={your computer name}.local
````

````
$ source ~/.bash_profile
$ sudo mkdir -p /opt/zimbra
$ mkdir ~/p4
$ mkdir ~/p4/main
$ p4 login
* enter your password
$ p4 client
````

Enter the following as the view contents:

````
//depot/zimbra/main/... //{workspace}/...
-//depot/zimbra/main/ThirdParty/... //{workspace}/ThirdParty/...
-//depot/zimbra/main/ThirdPartyBuilds/... //{workspace}/ThirdPartyBuilds/...
-//depot/zimbra/main/ZimbraAppliance/... //{workspace}/ZimbraAppliance/...
-//depot/zimbra/main/ZimbraDocs/... //{workspace}/ZimbraDocs/...
-//depot/zimbra/main/Prototypes/... //{workspace}/Prototypes/...
-//depot/zimbra/main/Support/... //{workspace}/Support/...
-//depot/zimbra/main/Gallery/... //{workspace}/Gallery/...
-//depot/zimbra/main/ZimbraSupportPortal/... //{workspace}/ZimbraSupportPortal/...
-//depot/zimbra/main/ZimbraQA/data/... //{workspace}/ZimbraQA/data/...
-//depot/zimbra/main/ZimbraPerf/data/... //{workspace}/ZimbraPerf/data/...
````

That view may have a lot more than you need, so you may want to consider explicitly listing
only what you need. Take a look at the clients of others in your group for examples.

````
$ cd ~/p4/main
$ p4 sync
````

## Install MariaDB

````
$ brew install mariadb
$ sudo ln -s /usr/local/opt/mariadb /opt/zimbra/mysql
$ sudo chown {username} /opt/zimbra
$ sudo vi /opt/zimbra/mysql/my.cnf (RHEL command, sudio vi /var/lib/mysql)
````

````
[mysqld]
port = 7306
socket = /opt/zimbra/mysql/data/mysql.sock
````

````
$ ln -sfv /usr/local/opt/mariadb/homebrew.mxcl.mariadb.plist ~/Library/LaunchAgents
$ launchctl load ~/Library/LaunchAgents/homebrew.mxcl.mariadb.plist

$ /opt/zimbra/mysql/bin/mysqladmin -S /opt/zimbra/mysql/data/mysql.sock -u root password zimbra
````

## Install Redis

````
$ brew install redis
$ ln -sfv /usr/local/opt/redis/*.plist ~/Library/LaunchAgents
$ launchctl load ~/Library/LaunchAgents/homebrew.mxcl.redis.plist
````

## Install Memcached

````
$ brew install memcached
$ ln -sfv /usr/local/opt/memcached/*.plist ~/Library/LaunchAgents
$ launchctl load ~/Library/LaunchAgents/homebrew.mxcl.memcached.plist
````

## Install JDK

Install the JDK 1.8 from Oracle if not already present on your system.

## Configure OpenLDAP

````
$ sudo visudo
{username}	ALL=NOPASSWD:/opt/zimbra/libexec/zmslapd
````

{username} is your local username. Be sure to insert a [TAB] between {username} and "ALL".


---


FOSS Edition - Build & Deploy
=============================

````
$ cd ~/p4/main/ZimbraServer
$ mkdir ~/p4/main/ZimbraWebClient/WebRoot/help
$ ant reset-all
$ ant -p
Buildfile: build.xml

Main targets:

   build-init              Creates directories required for compiling
   clean                   Deletes classes from build directories
   clean-opt-zimbra        Deletes deployed jars, classes, and zimlets
   dev-dist                Initializes build/dist
   dir-init                Creates directories in /opt/zimbra
   init-opt-zimbra         Copies build/dist to /opt/zimbra
   reset-all               Reset the world plus jetty and OpenLDAP
   reset-jetty             Resets jetty
   reset-open-ldap         Resets OpenLDAP
   reset-the-world         Reset the world
   reset-the-world-stage1  Cleans deployed files, compiles, and initializes /opt/zimbra.
   reset-the-world-stage2  Run when web server is running.
   service-deploy          Not just war file deployment, but a /opt/zimbra refresh as well!
   stop-webserver          Stops Jetty.  If Jetty is not installed, does nothing.
   test                    Run unit tests
  Default target: jar
````

## Test Running System

Login to WebMail. Open <http://localhost:7070/zimbra>

* Username: user1
* Password: test123

Login to Admin Console. Open <https://localhost:7071/zimbraAdmin>

* Username: admin
* Password: test123


---


NETWORK EDITION - Build & Deploy
================================

````
$ cd ~/p4/main/ZimbraServer
$ ant reset-all
 
$ cd ~/p4/main/ZimbraLicenseExtension
$ ant deploy-dev
 
$ cd ~/p4/main/ZimbraNetwork
$ ant dev-deploy
````

Many of the admin functions require that you deploy admin extensions.  These may require additional paths in your P4 workspace and additional services be deployed for those extensions to function correctly. ymmv.

Example for deploying an individual admin extension (ex. delegated admin extension):

````
$ cd ~p4/main/ZimbraNetwork/ZimbraAdminExt
$ ant -Dext.name=com_zimbra_delegatedadmin -Dext.dir=DelegatedAdmin deploy-zimlet
````
