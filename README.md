changed-maven-plugin
====================

Maven plugin showing which of your complex project dependencies were changed in VCS

Usage:
1.	Checkout http://vcs/wds/projects/maven-plugins/trunk/branching-plugin/ 
2.	mvn clean install
3.	Add the following to your MAVEN_HOME/settings.xml
 <pluginGroups>       
 ...
 <pluginGroup>com.macys.buildtools.maven.plugin</pluginGroup>
 ...
4.	Change working directory to one which has pom.xml which you want to know all dependencies and their change status
5.  If necessary add SCM information to your pom.xml (<scm>) see for details http://maven.apache.org/maven-release/maven-release-plugin/usage.html
6.	Type one of the following:
  -	mvn branching:changed 
  -	mvn branching:changed [-DnumberDaysToCheck=n], where n defines the number days before the current time. 
    This parameter limits the period which VCS commit is checked against. Default value is 30
    
7.	Ignore printed logs - there will be no somthing interesting.
8.	File with resulted info will be placed in the current directory and has name "<MODULE_NAME>.tree".
  Please note that if you have projects with several modules (reactor project) - the report file will be created for each of them
9.	In file you can see dependency tree with name of artifacts along with its version in brackets. Each node name line is followed by:
  - an error, if something went wrong
  - nothing, if there were no changes for defined period
  - Author, date, comment, revision information of the latest commit for defined period of time.
  
How it works?:
1.	It uses dependency tree builder from maven dependency plugin
2.	For each artifact in this tree it creates maven project in-memory.
3.	The project information can be located from local pom.xml located on file system or from m2 repo otherwise.
4.	Plugin tries to find <scm><connection> info in each project. Parent pom.xml is considered.
5.	It uses SCM maven provider to get list of SCM changes for specified period by connection defined on the previous step.
6.	It appends the latest commit to the output file.
Please note that you have to set correct scm information in order this plugin can tell information about changes. 

  

