package com.powerapi

import groovy.json.JsonBuilder
import javax.xml.xpath.*
import javax.xml.parsers.DocumentBuilderFactory
import groovy.json.JsonOutput


/**
 * Transform TestCSV to Json and send data on testdata index
 * @param testDataCSV test CSV string
 */
def static mapPowerapiCItoJson(PowerapiCI powerapiCI) {
    def content = new JsonBuilder()
    content(
            power: powerapiCI.power,
            timestamp: powerapiCI.timestamp,
            appName: powerapiCI.appName,
            testName: powerapiCI.testName,
            timeBeginTest: powerapiCI.timeBeginTest,
            timeEndTest: powerapiCI.timeEndTest,
            commitName: powerapiCI.commitName,
            testDuration: powerapiCI.testDuration,
            energy: powerapiCI.energy
    )
    return content.toString() + '\n'
}

/**
 * Send Post data to an url
 * @param url the target to send
 * @param queryString the query to send
 */
def sendPOSTMessage(String url, String queryString) {
    def baseUrl = new URL(url)

    HttpURLConnection connection = (HttpURLConnection) baseUrl.openConnection()
    connection.setRequestProperty("Content-Type", "application/x-ndjson")

    connection.requestMethod = 'POST'
    connection.doOutput = true

    byte[] postDataBytes = queryString.getBytes("UTF-8")
    connection.getOutputStream().write(postDataBytes)


    if (!(connection.responseCode < HttpURLConnection.HTTP_BAD_REQUEST)) {
        println("Youps.. Une erreur est survenue lors de l'envoie d'une donnée!")
    }
}

/**
 * Aggregate two lists to return list<PowerapiCI>
 * @param powerapiList list of PowerapiData
 * @param testList list of TestData
 * @param commitName the name of current commit
 * @return list <PowerapiCI>
 */
def
static findListPowerapiCI(List<PowerapiData> powerapiList, List<TestData> testList, String commitName, String appName) {
    List<PowerapiCI> powerapiCIList = new ArrayList<>()
    def powerList = new ArrayList<>()

    while (!testList.isEmpty()) {
        powerList.clear()
        def endTest = testList.pop()
        def beginTest = testList.find { it.testName == endTest.testName }
        testList.remove(beginTest)

        if (beginTest.timestamp > endTest.timestamp) {
            def tmp = beginTest
            beginTest = endTest
            endTest = tmp
        }

        double testDurationInMs = endTest.timestamp - beginTest.timestamp

        def allPowerapi = powerapiList.findAll({
            it.timestamp >= beginTest.timestamp && it.timestamp <= endTest.timestamp
        })

        for (PowerapiData papiD : allPowerapi) {
            powerList.add(papiD.power)
        }

        if (powerList.size() != 0) {
            def sumPowers = 0
            for (def power : powerList) {
                sumPowers += power
            }

            def averagePowerInMilliWatts = sumPowers / powerList.size()
            def energy = convertToJoule(averagePowerInMilliWatts, testDurationInMs)

            for (PowerapiData papiD : allPowerapi) {
                powerapiCIList.add(new PowerapiCI(papiD.power, papiD.timestamp, appName, beginTest.testName, commitName, beginTest.timestamp, endTest.timestamp, testDurationInMs, energy))
            }
        } else { /* Si aucune mesure n'a été prise pour ce test */
            powerapiCIList.add(new PowerapiCI(0d, beginTest.timestamp, appName, beginTest.testName, commitName, beginTest.timestamp, endTest.timestamp, 0l, 0d))
        }
    }
    addTestBeginPowers(powerapiCIList, powerapiList)

    return powerapiCIList
}

def static addTestBeginPowers(List<PowerapiCI> powerapiCIList, List<PowerapiData> powerapiList){
    List<PowerapiCI> powerapiCIListTMP = new ArrayList<>()
    //Map<List> testDatas = [name, begin, end, power, powerBefore, powerAfter, averagePower]

    //papiD.power, papiD.timestamp, appName, beginTest.testName, commitName, beginTest.timestamp, endTest.timestamp, testDurationInMs, energy
    def lastTestName = "begin"
    int cpt = 0
    long timeBefore
    long timeAfter
    def powerBefore
    def powerAfter
    def pSqrd
    def tSqrd
    def lSqrd

    //powerapiList.forEach({println powerapiList.timestamp})
    for (def test : powerapiCIList){
            if(test.testName != lastTestName) {
                println "nom du test " + test.testName
                println "début du test " + test.timeBeginTest
                powerBefore = 0
                powerAfter = Long.MAX_VALUE


                println "find"
                powerBefore = Collections.max(powerapiList.findAll {it.timestamp < test.timeBeginTest}).power
                powerAfter = Collections.min(powerapiList.findAll {it.timestamp > test.timeBeginTest}).power

                println "la valeur de puissance precedente est "  + powerBefore
                println "la valeur de puisasnce suivante est "  + powerAfter


                //TODO trouver et appliquer la formule
                   // tSqrd = Math.pow(papid.timestamp-timeBefore,2)
                   // pSqrd = Math.pow(powerBefore+
                   // lSqrd = pSqrd + tSqrd

                cpt++
                println cpt


              //  println testDatas
                //creation dune nouvelle liste ici avec chaque debut et fin de test
                //comparaison avec les timestamp precedent et suivant, calcul de la moyenne et ajout dans la liste initiale pour combler les trous

            }
            lastTestName = test.testName
     //   powerapiCIListTMP.add(new PowerapiCI(papid.power, test.timestamp, appName, test.testName, commitName, test.timeBeginTest, test.timeEndTest, test.testDuration, test.energy))

    }

    return powerapiCIList
}

/**
 * Send data to ES at an index
 * @param functionConvert function witch convert List<?> to JSon
 * @param index the index where send data
 * @param list the list List<?> of your data to be send
 */
def sendDataByPackage(def functionConvert, String index, List list) {
    /* Create header to send data */
    def header = new JsonBuilder()
    header.index(
            _index: index,
            _type: "doc"
    )

    while (!list.isEmpty()) {
        def jsonToSend = ""
        def toSend = list.take(Constants.NB_PAQUET)
        list = list.drop(Constants.NB_PAQUET)

        for (def cvsData : toSend) {
            jsonToSend += header.toString() + '\n'
            jsonToSend += functionConvert(cvsData)
        }
        sendPOSTMessage(Constants.ELASTIC_BULK_PATH, jsonToSend)
    }
}

/**
 * Search into and XML String a query
 * @param xml The xml String
 * @param xpathQuery The query to search
 * @return
 */
def static processXml(String xml, String xpathQuery) {
    def xpath = XPathFactory.newInstance().newXPath()
    def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    def inputStream = new ByteArrayInputStream(xml.bytes)
    def records = builder.parse(inputStream).documentElement
    xpath.evaluate(xpathQuery, records)
}

/**
 * Send data to elasticSearch
 * @param powerapiCSV
 * @param testCSV
 * @param commitName
 * @param appNameXML
 * @return
 */
def sendPowerapiAndTestCSV(String powerapiCSV, String testCSV, String commitName, String appNameXML) {
    def powerapi = powerapiCSV.split("mW").toList()
    List<PowerapiData> powerapiList = new ArrayList<>()
    powerapi.stream().each({ powerapiList.add(new PowerapiData(it)) })

    def test = testCSV.split("\n").toList()
    List<TestData> testList = new ArrayList<>()
    test.stream().each({ testList.add(new TestData(it)) })

    def appName = processXml(appNameXML, "//@name")
    def powerapiCIList = findListPowerapiCI(powerapiList, testList, commitName, appName)

    sendDataByPackage({ PowerapiCI p -> mapPowerapiCItoJson(p) }, "powerapici", powerapiCIList)
    println("Data correctly sent")

}

def static convertToJoule(double averagePowerInMilliWatts, double testDurationInMs) {

    double averagePowerInWatt = averagePowerInMilliWatts / 1000
    double durationInSec = testDurationInMs / 1000

    return averagePowerInWatt * durationInSec
}

sendPowerapiAndTestCSV("muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921613;targets=28098;devices=cpu;power=4900.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921657;targets=28098;devices=cpu;power=12250.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921757;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921766;targets=28098;devices=cpu;power=0.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921817;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921866;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921917;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042921966;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922017;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922067;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922117;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922166;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922217;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922317;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922366;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922417;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922517;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922567;targets=28098;devices=cpu;power=18375.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922617;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922717;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922767;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922816;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042922966;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923016;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923067;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923119;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923217;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923317;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923367;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923417;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923467;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923567;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923617;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923668;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923717;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923766;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923817;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923866;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042923967;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924016;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924067;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924117;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924217;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924266;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924317;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924416;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924468;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924518;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924666;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924719;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924817;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924867;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924917;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042924966;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925017;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925067;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925167;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925267;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925317;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925366;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925417;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925517;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925566;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925666;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925717;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925766;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925872;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925916;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042925967;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926016;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926266;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926317;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926366;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926417;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926466;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926517;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926716;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926816;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926867;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926916;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042926967;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927016;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927117;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927217;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927267;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927317;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927366;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927417;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927566;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927617;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927666;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927766;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927866;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042927966;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928017;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928066;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928167;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928217;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928317;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928417;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928467;targets=28098;devices=cpu;power=29400.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928517;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928616;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928767;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928866;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928917;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042928967;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929016;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929168;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929216;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929267;targets=28098;devices=cpu;power=36750.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929317;targets=28098;devices=cpu;power=12250.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929367;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929417;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929667;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929716;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929816;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929916;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042929967;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930017;targets=28098;devices=cpu;power=29400.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930067;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930117;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930217;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930317;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930366;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930417;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930466;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930517;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930566;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930617;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930666;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930817;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042930966;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931017;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931066;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931267;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931316;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931367;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931417;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931516;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931617;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931816;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931867;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042931966;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932017;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932066;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932167;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932217;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932267;targets=28098;devices=cpu;power=29400.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932317;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932367;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932417;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932466;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932616;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932717;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932767;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932917;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042932966;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933017;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933066;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933117;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933167;targets=28098;devices=cpu;power=36750.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933217;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933317;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933366;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933417;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933466;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933566;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933617;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933666;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933817;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933916;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042933967;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934017;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934117;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934317;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934366;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934417;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934516;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934567;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934667;targets=28098;devices=cpu;power=29400.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934716;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934767;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934817;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934867;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934916;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042934967;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935016;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935167;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935216;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935267;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935316;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935367;targets=28098;devices=cpu;power=14700.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935416;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935467;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935516;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935567;targets=28098;devices=cpu;power=36750.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935617;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935667;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935917;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042935967;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936017;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936117;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936216;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936267;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936316;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936416;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936516;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936567;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936667;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936717;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936917;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042936966;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937017;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937066;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937166;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937266;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937317;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937417;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937466;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937566;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937717;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937767;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937867;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937917;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042937967;targets=28098;devices=cpu;power=36750.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938017;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938067;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938117;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938267;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938316;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938417;targets=28098;devices=cpu;power=16333.333333333332 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938517;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938567;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938617;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938717;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938767;targets=28098;devices=cpu;power=29400.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938817;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938867;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938916;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042938967;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939016;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939067;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939117;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939167;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939217;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939266;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939317;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939367;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939416;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939467;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939517;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939566;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939617;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939667;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939716;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939767;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939817;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939867;targets=28098;devices=cpu;power=30625.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939917;targets=28098;devices=cpu;power=20416.666666666668 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042939967;targets=28098;devices=cpu;power=24500.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042940017;targets=28098;devices=cpu;power=19600.0 mW muid=b3269320-96fc-4236-af82-7eb1525daa2d;timestamp=1526042940067;targets=28098;devices=cpu;power=24500.0",
        "timestamp=1526042925285;testname=should_test_suite_fibonacci_use_puissance;startorend=start\n" +
                "timestamp=1526042939970;testname=should_test_suite_fibonacci_use_puissance;startorend=end\n" +
                "timestamp=1526042939970;testname=should_test_suite_fibonacci_courte;startorend=start\n" +
                "timestamp=1526042940020;testname=should_test_suite_fibonacci_courte;startorend=end\n" +
                "timestamp=1526042940020;testname=should_update_existing_hotel;startorend=start\n" +
                "timestamp=1526042940277;testname=should_update_existing_hotel;startorend=end\n" +
                "timestamp=1526042940279;testname=should_return_all_paginated_hotel;startorend=start\n" +
                "timestamp=1526042940388;testname=should_return_all_paginated_hotel;startorend=end\n" +
                "timestamp=1526042940388;testname=should_find_existing_hotel;startorend=start\n" +
                "timestamp=1526042940441;testname=should_find_existing_hotel;startorend=end\n" +
                "timestamp=1526042940442;testname=should_return_hotel_find_by_city;startorend=start\n" +
                "timestamp=1526042940523;testname=should_return_hotel_find_by_city;startorend=end\n" +
                "timestamp=1526042940524;testname=should_create_hotel;startorend=start\n" +
                "timestamp=1526042940571;testname=should_create_hotel;startorend=end\n" +
                "timestamp=1526042940577;testname=should_delete_existing_hotel;startorend=start\n" +
                "timestamp=1526042940623;testname=should_delete_existing_hotel;startorend=end",
        "commit",
        "<somthing name='coucou'></somthing>")

